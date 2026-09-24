package com.examprep.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 「我的」页面统计数据
 */
@Data
@Schema(description = "我的统计")
public class MyStatsVO {

    @Schema(description = "练习次数")
    private Long practiceCount;

    @Schema(description = "累计答题数")
    private Long totalAnswered;

    @Schema(description = "累计正确数")
    private Long totalCorrect;

    @Schema(description = "总正确率（0-100，一位小数）")
    private BigDecimal accuracy;

    @Schema(description = "错题本剩余（未掌握）")
    private Long wrongBookCount;

    @Schema(description = "收藏的资料数")
    private Long favoriteResourceCount;

    @Schema(description = "收藏的题目数")
    private Long favoriteQuestionCount;

    @Schema(description = "下载过的资料数")
    private Long downloadCount;

    @Schema(description = "我上传的资料数")
    private Long uploadCount;

    @Schema(description = "未读通知数")
    private Long unreadNoticeCount;
}
