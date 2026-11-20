package com.examprep.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
@Schema(description = "开始练习请求")
public class StartPracticeRequest {

    /**
     * 科目 ID（可选）。
     *
     * <p>2026-11-11 起改为可选：{@code subjectId} 与 {@code examCode} 都不传时表示
     * 「全库随机」——从全部 APPROVED 有效题目中等概率抽取，不默认限定任何科目。
     * practice_record.subject_id 允许 NULL，全库随机时该列写 NULL。
     */
    @Schema(description = "科目 ID（可选）；与 examCode 都不传 = 全库随机", example = "5")
    private Long subjectId;

    @Schema(description = "知识点 ID（可选，限定范围）")
    private Long knowledgeId;

    @Schema(description = "练习模式", allowableValues = {"SEQUENCE", "RANDOM", "BY_TYPE", "WRONG_BOOK"},
            example = "RANDOM")
    private String mode = "RANDOM";

    @Schema(description = "按题型练习时的题型（mode=BY_TYPE 时必填）", example = "SINGLE_CHOICE")
    private String questionType;

    @Min(value = 1, message = "难度最小为 1")
    @Max(value = 5, message = "难度最大为 5")
    @Schema(description = "按难度筛选（1-5），不传则不限", example = "3")
    private Integer difficulty;

    @Schema(description = "客观/主观筛选：MIXED 混合（默认）/ ONLY_OBJECTIVE 只刷客观 / ONLY_SUBJECTIVE 只刷主观",
            allowableValues = {"MIXED", "ONLY_OBJECTIVE", "ONLY_SUBJECTIVE"}, example = "MIXED")
    private String objectiveFilter;

    @Min(value = 1980, message = "年份最小 1980")
    @Max(value = 2100, message = "年份最大 2100")
    @Schema(description = "限定真题年份，如 2023；不传则不限年份（全部随机）。"
            + "对 SEQUENCE / RANDOM / BY_TYPE 生效；WRONG_BOOK（错题本）忽略该参数",
            example = "2023")
    private Integer year;

    @Schema(description = "限定考试类别编码，如 CET4 / GUOKAO / POSTGRAD_MATH1 / INTERVIEW；"
            + "不传则不限类别。与 year 同时传入按 AND 生效；WRONG_BOOK（错题本）忽略该参数",
            example = "CET4")
    private String examCode;

    @Min(value = 1, message = "题数至少 1")
    @Max(value = 200, message = "单次练习最多 200 题")
    @Schema(description = "本次题数，默认 20，上限 200", example = "20")
    private Integer count = 20;
}
