package com.examprep.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 提交答案后的即时反馈
 */
@Data
@Schema(description = "答题结果")
public class AnswerResultVO {

    @Schema(description = "题目 ID")
    private Long questionId;

    @Schema(description = "题型")
    private String type;

    @Schema(description = "是否已自动判分（false 表示主观题，需自评）")
    private Boolean judged;

    @Schema(description = "是否正确（judged=true 时有效）")
    private Boolean correct;

    @Schema(description = "正确答案文本（客观题返回）", example = "C")
    private String correctAnswer;

    @Schema(description = "解析（客观题返回）")
    private String analysis;

    @Schema(description = "参考答案（主观题返回）")
    private Object reference;

    @Schema(description = "评分要点（主观题返回）")
    private Object gradingPoints;

    @Schema(description = "是否需要自评（主观题为 true）")
    private Boolean needSelfEval;

    // ---------- 进度 ----------

    @Schema(description = "已答题数")
    private Integer answeredCount;

    @Schema(description = "总题数")
    private Integer totalCount;

    @Schema(description = "当前正确数")
    private Integer correctCount;
}
