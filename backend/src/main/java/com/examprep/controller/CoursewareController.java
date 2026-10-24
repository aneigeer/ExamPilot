package com.examprep.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.examprep.common.BizException;
import com.examprep.common.PageResult;
import com.examprep.common.Result;
import com.examprep.common.ResultCode;
import com.examprep.entity.AiChunk;
import com.examprep.entity.Courseware;
import com.examprep.mapper.AiChunkMapper;
import com.examprep.mapper.CoursewareMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 课件浏览接口（只读）
 *
 * <p>提供手机端浏览 634 份 docx 课件的能力：列表分页 + 详情（含前 20 块预览）。
 *
 * <p><b>与 AI 问答的关系</b>：课件正文存在 {@code ai_chunk}，检索时
 * {@code source_type='COURSEWARE'}，问答命中后 {@code refPath} 指向
 * {@code /courseware/{id}}，即本控制器的详情页 —— 保证「答案来源」可点开原文。
 *
 * <p><b>访问级别</b>：登录即可（不需要 ADMIN）。课件是学习资料，
 * 与题库浏览同级别，学生端要能直接看。
 */
@Slf4j
@Tag(name = "15. 课件浏览", description = "docx 课件列表与详情（学生端可读）")
@RestController
@RequestMapping("/courseware")
@RequiredArgsConstructor
public class CoursewareController {

    /** 详情页预览的最大块数（手机端够看，且不会一次拉爆响应体） */
    private static final int PREVIEW_CHUNK_LIMIT = 20;

    /** 每块预览截断长度（字符） */
    private static final int PREVIEW_CHAR_LIMIT = 200;

    private final CoursewareMapper coursewareMapper;
    private final AiChunkMapper aiChunkMapper;

    @Operation(summary = "课件列表（分页）",
            description = """
                    **访问级别：AUTH**（登录即可）。

                    支持按 `category` / `subjectId` 过滤。返回每份课件的块数，
                    块数由 `ai_chunk` 实时统计 —— **不冗余存储**，避免
                    「灌库成功但计数没更新」的不一致。
                    """)
    @GetMapping
    public Result<PageResult<Map<String, Object>>> list(
            @Parameter(description = "分类，如 数据结构") @RequestParam(required = false) String category,
            @Parameter(description = "科目 ID") @RequestParam(required = false) Long subjectId,
            @Parameter(description = "页码，从 1 开始") @RequestParam(defaultValue = "1") long page,
            @Parameter(description = "每页条数，最大 100") @RequestParam(defaultValue = "20") long size) {

        long safePage = Math.max(1, page);
        long safeSize = Math.max(1, Math.min(100, size));

        Page<Courseware> query = new Page<>(safePage, safeSize);
        Page<Courseware> result = coursewareMapper.selectPage(query, Wrappers.<Courseware>lambdaQuery()
                .eq(StringUtils.hasText(category), Courseware::getCategory, category)
                .eq(subjectId != null, Courseware::getSubjectId, subjectId)
                .orderByAsc(Courseware::getId));

        // 一次 GROUP BY 拿全部块数，避免逐条 count 造成 N+1
        Map<Long, Integer> chunkCounts = chunkCountsByCourseware();
        List<Map<String, Object>> rows = new ArrayList<>(result.getRecords().size());
        for (Courseware cw : result.getRecords()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", cw.getId());
            row.put("title", cw.getTitle());
            row.put("category", cw.getCategory());
            row.put("charCount", cw.getCharCount());
            row.put("chunkCount", chunkCounts.getOrDefault(cw.getId(), 0));
            rows.add(row);
        }
        return Result.success(PageResult.of(safePage, safeSize, result.getTotal(), rows));
    }

    @Operation(summary = "课件详情（含前 20 块预览）",
            description = """
                    **访问级别：AUTH**。

                    返回元信息 + 前 20 个检索块（`chunkNo` + 正文前 200 字），供手机端预览。
                    完整正文不入本接口 —— 课件动辄数万字，全量下发会拖垮移动端。
                    """)
    @GetMapping("/{id}")
    public Result<Map<String, Object>> detail(@PathVariable Long id) {
        Courseware cw = coursewareMapper.selectById(id);
        if (cw == null) {
            throw new BizException(ResultCode.NOT_FOUND);
        }

        List<AiChunk> chunks = aiChunkMapper.selectList(Wrappers.<AiChunk>lambdaQuery()
                .eq(AiChunk::getSourceType, AiChunk.SOURCE_COURSEWARE)
                .eq(AiChunk::getSourceId, id)
                .eq(AiChunk::getStatus, AiChunk.STATUS_ACTIVE)
                .orderByAsc(AiChunk::getChunkNo)
                .last("LIMIT " + PREVIEW_CHUNK_LIMIT));

        List<Map<String, Object>> previews = new ArrayList<>(chunks.size());
        for (AiChunk chunk : chunks) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("chunkNo", chunk.getChunkNo());
            item.put("content", truncate(chunk.getContent(), PREVIEW_CHAR_LIMIT));
            previews.add(item);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", cw.getId());
        data.put("title", cw.getTitle());
        data.put("category", cw.getCategory());
        data.put("subjectId", cw.getSubjectId());
        data.put("charCount", cw.getCharCount());
        data.put("chunkCount", chunkCountsByCourseware().getOrDefault(id, 0));
        data.put("chunks", previews);
        return Result.success(data);
    }

    /**
     * 批量取 COURSEWARE 各类件的 ACTIVE 块数
     *
     * @return coursewareId → 块数
     */
    private Map<Long, Integer> chunkCountsByCourseware() {
        List<Map<String, Object>> raw =
                coursewareMapper.countChunksGroupBySource(AiChunk.SOURCE_COURSEWARE);
        Map<Long, Integer> counts = new LinkedHashMap<>();
        for (Map<String, Object> row : raw) {
            Object sid = row.get("sourceId");
            Object cnt = row.get("cnt");
            if (sid instanceof Number n && cnt instanceof Number c) {
                counts.put(n.longValue(), c.intValue());
            }
        }
        return counts;
    }

    /** 按字符截断（不切断代理对，避免 emoji / 生僻字乱码） */
    private static String truncate(String text, int limit) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        if (flat.length() <= limit) {
            return flat;
        }
        int end = limit;
        if (Character.isHighSurrogate(flat.charAt(end - 1))) {
            end--;
        }
        return flat.substring(0, end) + "…";
    }
}
