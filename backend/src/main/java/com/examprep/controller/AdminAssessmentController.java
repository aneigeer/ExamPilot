package com.examprep.controller;

import com.examprep.common.Result;
import com.examprep.security.SecurityUtils;
import com.examprep.service.assessment.QuestionCalibrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 管理端：试题校准与测评看板
 *
 * <p>读接口 AUDITOR / ADMIN；触发校准仅 ADMIN。
 */
@Tag(name = "09C. 后台-测评中台", description = "试题难度/区分度校准、异常题清单、模考概览")
@RestController
@RequestMapping("/admin/assessment")
@RequiredArgsConstructor
public class AdminAssessmentController {

    private final QuestionCalibrationService calibrationService;

    @Operation(summary = "触发试题校准",
            description = "**访问级别：ROLE:ADMIN**。基于练习与模考真实作答重算正确率、难度与区分度，并登记 CALIBRATION 流水。")
    @PostMapping("/calibrate")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<QuestionCalibrationService.CalibrationResult> calibrate() {
        Long uid = SecurityUtils.currentUserIdOrNull();
        return Result.success("校准完成",
                calibrationService.recalibrate("manual:admin:" + (uid == null ? "system" : uid)));
    }

    @Operation(summary = "校准概览",
            description = "**访问级别：ROLE:AUDITOR / ROLE:ADMIN**。难度分布、校准覆盖率、异常题数、近期模考平均分与完成率。")
    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('AUDITOR','ADMIN')")
    public Result<Map<String, Object>> summary(@RequestParam(defaultValue = "30") int mockDays) {
        return Result.success(calibrationService.summary(mockDays));
    }

    @Operation(summary = "异常题清单",
            description = "**访问级别：ROLE:AUDITOR / ROLE:ADMIN**。高错误率（实测正确率 < 30%）或负区分度（疑似错题/偏题）。")
    @GetMapping("/anomalies")
    @PreAuthorize("hasAnyRole('AUDITOR','ADMIN')")
    public Result<List<Map<String, Object>>> anomalies(@RequestParam(defaultValue = "50") int limit) {
        return Result.success(calibrationService.anomalies(limit));
    }
}
