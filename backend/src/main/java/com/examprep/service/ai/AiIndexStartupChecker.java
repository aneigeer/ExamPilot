package com.examprep.service.ai;

import com.examprep.entity.AiChunk;
import com.examprep.entity.Question;
import com.examprep.entity.Resource;
import com.examprep.mapper.QuestionMapper;
import com.examprep.mapper.ResourceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 索引启动自检（设计方案 A.6 触发点 ⑤）
 *
 * <p><b>只告警，不自动重建</b>。
 *
 * <p>理由：全文重建耗时随语料线性增长，自动重建会在生产启动时造成不可预期的延迟；
 * 使用场景启动抖动比「索引慢了 1 分钟」致命得多。是否重建由人决定（点 reindex）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiIndexStartupChecker implements ApplicationRunner {

    private final ChunkSyncService chunkSyncService;
    private final ChunkExtractor chunkExtractor;
    private final QuestionMapper questionMapper;
    private final ResourceMapper resourceMapper;

    @Override
    public void run(ApplicationArguments args) {
        try {
            long chunkCount = chunkSyncService.indexStats()
                    .getOrDefault("chunk", 0L) instanceof Number n ? n.longValue() : 0L;
            if (chunkCount == 0) {
                log.warn("[AI] 语料索引为空，AI 问答将全部拒答。"
                        + "请执行 sql/migration-ai.sql 建表后调用 POST /api/admin/ai/reindex 重建索引");
                return;
            }
            LocalDateTime indexTime = chunkSyncService.checkIndexFreshness();
            LocalDateTime latest = latestSourceUpdatedAt();
            if (indexTime != null && latest != null && latest.isAfter(indexTime)) {
                log.warn("[AI] 索引落后于业务数据（索引 {} < 业务 {}），"
                        + "请调用 POST /api/admin/ai/reindex 重建（系统不自动重建，避免启动抖动）",
                        indexTime, latest);
            } else {
                log.info("[AI] 索引自检通过：{} 个语料块，最近更新时间 {}", chunkCount, indexTime);
            }
        } catch (Exception e) {
            // 索引表尚未建 / 数据库未就绪：只告警，不阻断应用启动
            log.warn("[AI] 索引自检跳过（可能尚未执行 sql/migration-ai.sql）：{}", e.getMessage());
        }
    }

    /** 业务侧已审核内容的最新更新时间 */
    private LocalDateTime latestSourceUpdatedAt() {
        List<Question> questions = questionMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<Question>lambdaQuery()
                        .eq(Question::getAuditStatus, "APPROVED")
                        .orderByDesc(Question::getUpdatedAt)
                        .last("LIMIT 1"));
        List<Resource> resources = resourceMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<Resource>lambdaQuery()
                        .eq(Resource::getAuditStatus, "APPROVED")
                        .orderByDesc(Resource::getUpdatedAt)
                        .last("LIMIT 1"));
        LocalDateTime latest = null;
        for (Question q : questions) {
            if (q.getUpdatedAt() != null && (latest == null || q.getUpdatedAt().isAfter(latest))) {
                latest = q.getUpdatedAt();
            }
        }
        for (Resource r : resources) {
            if (r.getUpdatedAt() != null && (latest == null || r.getUpdatedAt().isAfter(latest))) {
                latest = r.getUpdatedAt();
            }
        }
        return latest;
    }

    /** 便于外部检查：当前是否有可用索引 */
    public boolean indexReady() {
        Object v = chunkSyncService.indexStats().get("chunk");
        return v instanceof Number n && n.longValue() > 0;
    }

    /** 语料是否为空（供日志文案） */
    public boolean isEmpty() {
        Object v = chunkSyncService.indexStats().get("chunk");
        return !StringUtils.hasText(String.valueOf(v)) || "0".equals(String.valueOf(v));
    }

    /** 来源类型常量（避免魔法字符串） */
    public static String questionSourceType() {
        return AiChunk.SOURCE_QUESTION;
    }
}
