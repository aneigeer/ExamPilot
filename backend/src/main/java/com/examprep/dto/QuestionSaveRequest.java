package com.examprep.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 题目新增 / 编辑请求（全题型统一结构）
 *
 * 按题型填不同部分：
 * - 单选/多选/判断：填 options（isAnswer 标记正确答案）
 * - 填空：填 answerContent.blanks + caseSensitive
 * - 主观题：填 answerContent.reference + gradingPoints
 */
@Data
@Schema(description = "题目保存请求")
public class QuestionSaveRequest {

    @NotNull(message = "科目不能为空")
    @Schema(description = "科目 ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "5")
    private Long subjectId;

    @NotBlank(message = "题型不能为空")
    @Schema(description = "题型", requiredMode = Schema.RequiredMode.REQUIRED, example = "SINGLE_CHOICE")
    private String type;

    @NotBlank(message = "题干不能为空")
    @Schema(description = "题干", requiredMode = Schema.RequiredMode.REQUIRED)
    private String stem;

    @Schema(description = "材料正文（组合题 / 阅读题用）")
    private String material;

    @Schema(description = "难度 1-5", example = "3")
    private Integer difficulty;

    @Schema(description = "来源", example = "模拟题")
    private String source;

    @Schema(description = "标签，逗号分隔", example = "基础,数据类型")
    private String tags;

    @Schema(description = "解析")
    private String analysis;

    @Schema(description = "分值", example = "2")
    private Integer score;

    @Schema(description = "父题 ID（组合题子题用，不填为独立题）")
    private Long parentId;

    @Schema(description = "选项列表（客观题必填）")
    private List<OptionItem> options;

    @Schema(description = "答案内容（填空：{\"blanks\":[\"printf\"]}；简答：{\"reference\":\"...\",\"keywords\":[\"...\"]}）")
    private Map<String, Object> answerContent;

    @Schema(description = "评分要点（主观题）")
    private List<String> gradingPoints;

    @Schema(description = "填空是否大小写敏感")
    private Boolean caseSensitive;

    @Schema(description = "关联知识点 ID 列表", example = "[2,3]")
    private List<Long> knowledgeIds;

    @Data
    @Schema(description = "选项")
    public static class OptionItem {

        @NotBlank(message = "选项标识不能为空")
        @Schema(description = "选项标识", example = "A")
        private String key;

        @NotBlank(message = "选项内容不能为空")
        @Schema(description = "选项内容")
        private String content;

        @Schema(description = "是否为正确答案")
        private Boolean isAnswer;
    }
}
