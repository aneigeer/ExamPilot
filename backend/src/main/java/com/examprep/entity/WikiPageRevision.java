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
 * Wiki 草稿修订（LLM 蒸馏产物，必须人工审核）
 *
 * <p>状态机：{@code PENDING → APPROVED / REJECTED}。
 * 同一 slug 通过生成列 {@code pending_slug} + UNIQUE 约束保证
 * 同时只有一条待审草稿——重复蒸馏只更新草稿，不会堆重复稿。
 */
@Data
@TableName("wiki_page_revision")
@Schema(description = "Wiki 草稿修订")
public class WikiPageRevision {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 已有页面 ID；首次蒸馏为 null */
    private Long pageId;

    private String slug;

    private String title;

    private Long subjectId;

    private Long knowledgeId;

    private String summary;

    private String content;

    /** 要点 JSON 数组 */
    private String keyPoints;

    /** 来源 ai_chunk.id JSON 数组 */
    private String sourceChunkIds;

    /** LLM 提议的页面链接 JSON 数组 */
    private String linksJson;

    private String model;

    private BigDecimal qualityScore;

    @Schema(description = "PENDING / APPROVED / REJECTED")
    private String status;

    /** 来源 async_task.id */
    private Long taskId;

    private Long reviewerId;

    private String reviewNote;

    private LocalDateTime createdAt;

    private LocalDateTime reviewedAt;

    /** 生成列，只读 */
    @TableField(exist = false)
    private String pendingSlug;
}
