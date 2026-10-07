package com.examprep.service.ai;

import java.util.List;

/**
 * 溯源引用（强制溯源在此落地）
 *
 * <p><b>不变量</b>：任何情况下 {@code sources} 都不为 null，无来源时返回空 list。
 * App 端据此渲染「参考来源」列表，点击 {@code refPath} 跳转原文。
 *
 * @param chunkId  语料块 ID
 * @param title    来源标题
 * @param snippet  命中片段（抽取式会附带句子在原文中的偏移，供 App 高亮）
 * @param refType  跳转类型 QUESTION / RESOURCE / KNOWLEDGE
 * @param refId    跳转业务 ID
 * @param refPath  跳转路径
 * @param offset   片段在 chunk 内容中的起始偏移，-1 表示不提供
 */
public record SourceRef(
        Long chunkId,
        String title,
        String snippet,
        String refType,
        Long refId,
        String refPath,
        int offset
) {

    /** 构造不带偏移的引用（LLM 引用场景用） */
    public static SourceRef of(Long chunkId, String title, String snippet,
                               String refType, Long refId, String refPath) {
        return new SourceRef(chunkId, title, snippet, refType, refId, refPath, -1);
    }

    /** 空来源列表（拒答场景） */
    public static List<SourceRef> emptyList() {
        return List.of();
    }
}
