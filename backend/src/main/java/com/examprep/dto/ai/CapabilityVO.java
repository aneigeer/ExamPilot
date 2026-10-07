package com.examprep.dto.ai;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Map;

/**
 * AI 能力探测（App 启动时调，决定是否显示 AI 入口）
 */
@Data
@Schema(description = "AI 能力探测结果")
public class CapabilityVO {

    @Schema(description = "AI 问答是否可用（索引有语料即可用；无大模型 Key 时走抽取式）")
    private Boolean available;

    @Schema(description = "当前生效的生成器：dashscope / zhipu / extractive",
            example = "extractive")
    private String generator;

    @Schema(description = "配置的大模型 provider")
    private String provider;

    @Schema(description = "是否已配置大模型 API Key")
    private Boolean llmConfigured;

    @Schema(description = "是否处于降级中（Key 缺失或熔断）")
    private Boolean degraded;

    @Schema(description = "降级原因（Key 未配置 / 熔断中 / 无 Key 时抽取式可用）")
    private String degradedReason;

    @Schema(description = "是否正在熔断（连续失败 3 次触发，持续 60 秒）")
    private Boolean circuitOpen;

    @Schema(description = "熔断剩余毫秒数；0 表示未熔断")
    private Long circuitRemainingMillis;

    @Schema(description = "是否使用本地抽取模式（App 端展示「本地抽取」提示）")
    private Boolean extractiveMode;

    @Schema(description = "索引语料块总数")
    private Long chunkCount;

    @Schema(description = "拒答阈值（低于此分直接拒答，不进生成环节）")
    private Double rejectThreshold;

    @Schema(description = "索引现状统计（按来源类型分块数）")
    private Map<String, Object> indexStats;

    // ==================================================================
    // T09 / T10 扩展字段（只增不改，App 端已有调用逻辑不变）
    // ==================================================================

    @Schema(description = "向量通道是否已开启（配置层面）")
    private Boolean vectorEnabled;

    @Schema(description = "★ 向量索引是否就绪；false 表示已降级为基础检索（ngram），入口不隐藏")
    private Boolean vectorIndexReady;

    @Schema(description = "向量通道是否可用（健康检查通过：embedding + qdrant 同时就绪）")
    private Boolean vectorChannelAvailable;

    @Schema(description = "向量通道降级原因；可用时为 null")
    private String vectorDegradedReason;

    @Schema(description = "AI 微服务自报状态：ok / degraded / UNREACHABLE")
    private String aiServiceStatus;

    @Schema(description = "embedding 模型标识（换模型需重建索引）")
    private String embeddingModel;

    @Schema(description = "embedding 向量维度")
    private Integer embeddingDim;

    @Schema(description = "OCR 能力是否就绪（T11）")
    private Boolean ocrEnabled;

    @Schema(description = "当前存储提供方：minio / local（MinIO 挂时自动回落 local）")
    private String storageProvider;
}
