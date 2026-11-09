package com.examprep.service.ai.agent;

import com.examprep.common.BizException;
import com.examprep.common.ResultCode;
import com.examprep.config.ObservabilityProperties;
import com.examprep.mapper.AiAgentRunMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Agent 运行轨迹保留期清理（数据最小化）。
 *
 * <p><b>为什么必须有</b>：{@code ai_agent_run} 存的是「用户问题原文 + 模型回答 + 完整事件轨迹」，
 * 是全库最敏感的表；只增不删等于永久留存，与「最小必要」原则冲突。
 * 本服务按 {@code app.observability.retention-days}（默认 90 天）
 * <b>物理删除</b>过期行——不软删，因为软删只是换个地方存。
 *
 * <p><b>分批而不是一条 DELETE</b>：大数据量下单条 DELETE 会长时间持有行锁/产生大事务，
 * 把正在收尾的 SSE 写入（{@code runService.finish}）卡死。因此每批
 * {@code retention-batch}（默认 500）行，循环到删不动为止；日志<b>只记总条数</b>，
 * 不打印被删内容（打印内容等于把数据又搬进日志）。
 *
 * <p><b>失败不抛</b>：定时任务抛异常不会有人看，且下次还会跑；这里捕获并 ERROR 记录，
 * 避免「清理失败」升级成「服务异常」。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentRunRetentionService {

    /** 允许的最小保留天数 */
    public static final int MIN_DAYS = 1;

    /** 允许的最大保留天数（≈10 年，超过基本等于关闭清理） */
    public static final int MAX_DAYS = 3650;

    /** 循环上限（防止极端情况下无限删；500 × 1000 = 50 万行/次） */
    private static final int MAX_ROUNDS = 1000;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AiAgentRunMapper runMapper;

    private final ObservabilityProperties observability;

    /**
     * 保留期清理结果。
     *
     * @param deleted 实际删除行数
     * @param days    保留天数
     * @param cutoff  删除边界（早于该时间的行被删），格式 {@code yyyy-MM-dd HH:mm:ss}
     */
    public record PurgeResult(long deleted, int days, String cutoff) {
    }

    /**
     * 定时清理（默认每天 04:10，业务低峰）。
     *
     * <p>cron 走配置 {@code app.observability.retention-cron}，便于运维不改代码调整。
     */
    @Scheduled(cron = "${app.observability.retention-cron:0 10 4 * * ?}")
    public void scheduledPurge() {
        int days = observability.getRetentionDays();
        try {
            PurgeResult result = purge(days);
            log.info("[Retention] 定时清理完成 days={} cutoff={} deleted={}",
                    result.days(), result.cutoff(), result.deleted());
        } catch (Exception e) {
            // 定时任务异常不能上抛（没人接），但必须留痕
            log.error("[Retention] 定时清理失败 days={}", days, e);
        }
    }

    /**
     * 手工清理（管理端 {@code POST /admin/ai/retention/purge} 调用）。
     *
     * @param days 保留天数，允许 {@value #MIN_DAYS}..{@value #MAX_DAYS}
     * @return 删除统计
     * @throws BizException 天数越界（HTTP 400），<b>不静默 clamp</b>
     */
    public PurgeResult purge(int days) {
        if (days < MIN_DAYS || days > MAX_DAYS) {
            throw new BizException(ResultCode.PARAM_ERROR,
                    "days 必须在 " + MIN_DAYS + ".." + MAX_DAYS + " 之间（当前 " + days + "）");
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(days);
        int batch = Math.max(1, observability.getRetentionBatch());

        long total = 0L;
        int rounds = 0;
        while (rounds++ < MAX_ROUNDS) {
            int deleted = runMapper.deleteOlderThan(cutoff, batch);
            if (deleted <= 0) {
                break;
            }
            total += deleted;
            if (deleted < batch) {
                // 最后一批（不满批说明已删完），省一次空查询
                break;
            }
        }
        if (rounds >= MAX_ROUNDS) {
            log.warn("[Retention] 达到单次轮次上限 {}，仍有数据未清理，下次继续（days={}）",
                    MAX_ROUNDS, days);
        }
        log.info("[Retention] 清理完成 days={} cutoff={} deleted={}", days, cutoff.format(TS), total);
        return new PurgeResult(total, days, cutoff.format(TS));
    }

    /**
     * 预读：按当前配置还要删多少行（只读，给管理端确认弹窗用）。
     *
     * @param days 保留天数，允许 {@value #MIN_DAYS}..{@value #MAX_DAYS}
     * @return 待删行数
     */
    public long preview(int days) {
        if (days < MIN_DAYS || days > MAX_DAYS) {
            throw new BizException(ResultCode.PARAM_ERROR,
                    "days 必须在 " + MIN_DAYS + ".." + MAX_DAYS + " 之间（当前 " + days + "）");
        }
        return runMapper.countOlderThan(LocalDateTime.now().minusDays(days));
    }
}
