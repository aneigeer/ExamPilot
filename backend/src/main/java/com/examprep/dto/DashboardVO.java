package com.examprep.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "管理端仪表盘统计")
public class DashboardVO {

    @Schema(description = "用户总数")
    private Long userCount;

    @Schema(description = "题目总数")
    private Long questionCount;

    @Schema(description = "资料总数（已通过审核）")
    private Long resourceCount;

    @Schema(description = "待审核资料数")
    private Long pendingResourceCount;

    @Schema(description = "待审核题目数")
    private Long pendingQuestionCount;

    @Schema(description = "待处理举报数")
    private Long pendingReportCount;

    @Schema(description = "累计下载量")
    private Long downloadCount;

    @Schema(description = "今日新增资料")
    private Long todayResourceCount;
}
