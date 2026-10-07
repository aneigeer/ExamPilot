package com.examprep.dto.ai;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 溯源来源（App 端可点击跳转）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "答案溯源来源")
public class SourceRefVO {

    @Schema(description = "语料块 ID")
    private Long chunkId;

    @Schema(description = "来源标题", example = "简述C语言中「值传递」与「地址传递」的区别")
    private String title;

    @Schema(description = "命中片段")
    private String snippet;

    @Schema(description = "跳转类型：QUESTION / RESOURCE / KNOWLEDGE", example = "QUESTION")
    private String refType;

    @Schema(description = "跳转业务 ID")
    private Long refId;

    @Schema(description = "App 端跳转路径", example = "/questions/5")
    private String refPath;

    @Schema(description = "片段在原文中的起始偏移，-1 表示不提供（App 端可据此高亮）")
    private Integer offset;
}
