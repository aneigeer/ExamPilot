package com.examprep.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Wiki 主题页（已发布的「热知识层」）
 *
 * <p><b>与 RAG 的关系</b>：RAG（{@code ai_chunk}）保证即时可用与长尾召回；
 * 本表是后台异步蒸馏出的结构化主题页，命中时直接返回预编译内容，
 * 未命中再降级到 RAG。两者共享同一份原始文档，互不阻塞。
 *
 * <p>正文只由人工审核后的 {@link WikiPageRevision} 复制而来，
 * LLM 草稿永远先落在修订表，不直接写本表。
 */
@Data
@TableName("wiki_page")
@Schema(description = "Wiki 主题页")
public class WikiPage {

    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_OFFLINE = "OFFLINE";

    @TableId(type = IdType.AUTO)
    private Long id;

    @Schema(description = "稳定标识：kp-{知识点ID} / doc-{哈希}")
    private String slug;

    private String title;

    private Long subjectId;

    private Long knowledgeId;

    @Schema(description = "摘要（同时参与向量检索）")
    private String summary;

    @Schema(description = "正文（Markdown）")
    private String content;

    @Schema(description = "要点 JSON 数组")
    private String keyPoints;

    @Schema(description = "来源 ai_chunk.id JSON 数组（强制溯源）")
    private String sourceChunkIds;

    @Schema(description = "PUBLISHED / OFFLINE")
    private String status;

    /** 信任分级：VERIFIED / AUTO_DISTILLED / STALE_REVIEW / RETIRED */
    private String trustTier;

    /** 引用来源块的聚合 hash */
    private String sourceHash;

    /** 过期时间（为空表示不过期） */
    private java.time.LocalDateTime expiresAt;

    /** 标旧原因 */
    private String staleReason;

    private Integer version;

    @Schema(description = "蒸馏模型标识")
    private String model;

    private BigDecimal qualityScore;

    private LocalDateTime publishedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /** 全文检索临时分（仅查询回填，不落库） */
    @TableField(exist = false)
    private Double ftScore;

    public boolean isPublished() {
        return STATUS_PUBLISHED.equals(status);
    }
}
