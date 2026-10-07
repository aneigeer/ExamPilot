package com.examprep.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 检索语料块（RAG 检索层的最小检索单元）
 *
 * <p>由 {@code ChunkExtractor} 从已审核（APPROVED）的题目 / 资料 / 知识点抽取，
 * 经 {@code ChunkSyncService} 幂等写入。检索侧由 {@code RetrievalService}
 * 走 MySQL 内置 ngram 全文索引召回后重排。
 *
 * <p><b>生命周期约定</b>：本表是<b>派生数据</b>，不设逻辑删除字段，
 * 有效性由 {@code status} 表达（ACTIVE / INVALID）。
 * 来源被下架时置 INVALID 而非物理删除，保留可追溯性便于排查。
 */
@Data
@TableName("ai_chunk")
@Schema(description = "AI 检索语料块")
public class AiChunk {

    /** 来源类型：题目 */
    public static final String SOURCE_QUESTION = "QUESTION";
    /** 来源类型：资料 */
    public static final String SOURCE_RESOURCE = "RESOURCE";
    /** 来源类型：知识点 */
    public static final String SOURCE_KNOWLEDGE = "KNOWLEDGE";

    /** 状态：有效 */
    public static final String STATUS_ACTIVE = "ACTIVE";
    /** 状态：失效（来源已下架 / 块已超出新的分块数量） */
    public static final String STATUS_INVALID = "INVALID";

    @TableId(type = IdType.AUTO)
    private Long id;

    @Schema(description = "来源类型：QUESTION / RESOURCE / KNOWLEDGE", example = "QUESTION")
    private String sourceType;

    @Schema(description = "来源业务 ID")
    private Long sourceId;

    @Schema(description = "块序号，0 为未分块（题目 / 知识点恒为 0）")
    private Integer chunkNo;

    @Schema(description = "来源标题（冗余存储，进全文索引，支持标题命中加权）")
    private String title;

    @Schema(description = "切块后的检索文本")
    private String content;

    @Schema(description = "考试类别，用于跨考试隔离检索")
    private Long examCategoryId;

    @Schema(description = "科目 ID")
    private Long subjectId;

    @Schema(description = "知识点 ID（画像与推荐的关键连接键）")
    private Long knowledgeId;

    @Schema(description = "难度 1-5；资料与知识点固定 3", example = "3")
    private Integer difficulty;

    @Schema(description = "跳转类型：QUESTION / RESOURCE / KNOWLEDGE", example = "QUESTION")
    private String refType;

    @Schema(description = "跳转业务 ID，App 端点击来源时使用")
    private Long refId;

    @Schema(description = "PDF 来源页码（1 起）；非 PDF 来源为 null")
    private Integer pageNo;

    @Schema(description = "MD5(content)，幂等更新判据，无变化不重建索引")
    private String contentHash;

    @Schema(description = "来源业务对象的更新时间，用于增量与时间衰减")
    private LocalDateTime sourceUpdatedAt;

    @Schema(description = "ACTIVE / INVALID", example = "ACTIVE")
    private String status;

    /**
     * 向量化同步状态：0 待同步 / 1 已同步 / 2 失败（下次对账重试）
     *
     * <p><b>向量本体存在 Qdrant，不存 MySQL</b>：MySQL 8.0 无原生 VECTOR 类型，
     * 硬存只能 BLOB 且无法建 HNSW 索引。此字段只记状态，供异步同步与对账使用。
     */
    @Schema(description = "向量化状态：0待同步 1已同步 2失败", example = "0")
    private Integer vectorSynced;

    @Schema(description = "向量同步时间")
    private LocalDateTime vectorSyncedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /** 是否有效索引块 */
    public boolean isActive() {
        return STATUS_ACTIVE.equals(this.status);
    }

    /** 向量同步状态常量 */
    public static final int VECTOR_PENDING = 0;
    public static final int VECTOR_SYNCED = 1;
    public static final int VECTOR_FAILED = 2;
}
