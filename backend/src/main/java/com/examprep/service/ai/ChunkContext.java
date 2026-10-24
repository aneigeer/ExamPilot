package com.examprep.service.ai;

import java.time.LocalDateTime;

/**
 * 检索命中的语料块上下文（生成环节的输入单元）
 *
 * @param chunkId          ai_chunk 主键
 * @param sourceType       来源类型 QUESTION / RESOURCE / KNOWLEDGE
 * @param sourceId         来源业务 ID
 * @param title            来源标题
 * @param content          切块后的检索文本
 * @param subjectId        科目 ID
 * @param knowledgeId      知识点 ID（可能为 null）
 * @param difficulty       难度 1-5
 * @param refType          跳转类型
 * @param refId            跳转业务 ID
 * @param pageNo           PDF 页码（可能为 null）
 * @param sourceUpdatedAt  来源更新时间（时间衰减用）
 * @param hotCount         热度（下载量 + 浏览量）
 * @param ftScore          全文相关度原始分（LIKE 降级时为 0）
 * @param finalScore       五因子重排后的最终分（0-1 归一前）
 */
public record ChunkContext(
        Long chunkId,
        String sourceType,
        Long sourceId,
        String title,
        String content,
        Long subjectId,
        Long knowledgeId,
        Integer difficulty,
        String refType,
        Long refId,
        Integer pageNo,
        LocalDateTime sourceUpdatedAt,
        long hotCount,
        double ftScore,
        double finalScore
) {

    /**
     * App 端跳转路径
     *
     * <p>题目 {@code /questions/{id}}、资料 {@code /resources/{id}}、
     * 知识点 {@code /knowledge/{id}}、课件 {@code /courseware/{id}}、
     * Wiki 主题页 {@code /wiki/{id}}。
     *
     * <p>⚠️ <b>新增 source_type 时必须在这里加分支</b>，否则会落到
     * {@code default → /home}：客户端点「来源」会跳到首页而不是原文，
     * 且这种 bug 不会报错、极难发现。
     */
    public String refPath() {
        if (refId == null) {
            return "/home";
        }
        String prefix = switch (refType == null ? "" : refType) {
            case "QUESTION" -> "/questions/";
            case "RESOURCE" -> "/resources/";
            case "KNOWLEDGE" -> "/knowledge/";
            case "COURSEWARE" -> "/courseware/";
            // Wiki 主题页：Agent 工具 search_wiki 返回的上下文也走这里
            case "WIKI" -> "/wiki/";
            default -> null;
        };
        // 未知类型不能拼出 "/home8" 这种畸形路径（客户端会 404），直接回首页
        return prefix == null ? "/home" : prefix + refId;
    }

    /** 带最终分的新实例（重排阶段使用） */
    public ChunkContext withFinalScore(double score) {
        return new ChunkContext(chunkId, sourceType, sourceId, title, content, subjectId,
                knowledgeId, difficulty, refType, refId, pageNo, sourceUpdatedAt,
                hotCount, ftScore, score);
    }
}
