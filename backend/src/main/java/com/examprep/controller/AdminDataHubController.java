package com.examprep.controller;

import com.examprep.common.PageResult;
import com.examprep.common.Result;
import com.examprep.entity.DataQualityIssue;
import com.examprep.security.SecurityUtils;
import com.examprep.service.datahub.DataHubService;
import com.examprep.service.datahub.DataMetricsEtlService;
import com.examprep.service.datahub.DataQualityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 管理端：数据中台
 *
 * <p>权限：
 * <ul>
 *   <li>读接口（overview / coverage / trends / pipelines / quality-issues / metric-dictionary）
 *       → AUDITOR 或 ADMIN</li>
 *   <li>写接口（run-etl / run-quality-scan）→ 仅 ADMIN</li>
 * </ul>
 */
@Tag(name = "09B. 后台-数据中台", description = "资产治理、分层指标、质量巡检")
@RestController
@RequestMapping("/admin/data-hub")
@RequiredArgsConstructor
public class AdminDataHubController {

    private final DataHubService dataHubService;
    private final DataMetricsEtlService metricsEtlService;
    private final DataQualityService qualityService;

    @Operation(summary = "资产总览",
            description = "**访问级别：ROLE:AUDITOR / ROLE:ADMIN**。总题量 / 存活 / 已过审 / 向量块 / 质量健康分 / 最近 ETL。")
    @GetMapping("/overview")
    @PreAuthorize("hasAnyRole('AUDITOR','ADMIN')")
    public Result<Map<String, Object>> overview() {
        return Result.success(dataHubService.overview());
    }

    @Operation(summary = "覆盖矩阵",
            description = "**访问级别：ROLE:AUDITOR / ROLE:ADMIN**。按考试类别 + 科目展示题量、向量、课件、知识点与覆盖健康度；`gapOnly=true` 只看缺口。")
    @GetMapping("/coverage")
    @PreAuthorize("hasAnyRole('AUDITOR','ADMIN')")
    public Result<List<Map<String, Object>>> coverage(
            @RequestParam(required = false) String examCode,
            @RequestParam(required = false) Boolean gapOnly) {
        return Result.success(dataHubService.coverage(examCode, gapOnly));
    }

    @Operation(summary = "学习与 AI 趋势",
            description = "**访问级别：ROLE:AUDITOR / ROLE:ADMIN**。近 N 天（1-180，默认 30）日指标，缺失日期补 0。")
    @GetMapping("/trends")
    @PreAuthorize("hasAnyRole('AUDITOR','ADMIN')")
    public Result<List<Map<String, Object>>> trends(
            @RequestParam(defaultValue = "30") Integer days) {
        return Result.success(dataHubService.trends(days));
    }

    @Operation(summary = "数据流水线列表",
            description = "**访问级别：ROLE:AUDITOR / ROLE:ADMIN**。按 ID 倒序，默认 20 条，最多 100 条。")
    @GetMapping("/pipelines")
    @PreAuthorize("hasAnyRole('AUDITOR','ADMIN')")
    public Result<List<Map<String, Object>>> pipelines(
            @RequestParam(defaultValue = "20") Integer limit) {
        return Result.success(dataHubService.pipelines(limit));
    }

    @Operation(summary = "数据质量告警",
            description = "**访问级别：ROLE:AUDITOR / ROLE:ADMIN**。支持 issueType / severity / status 筛选与分页。")
    @GetMapping("/quality-issues")
    @PreAuthorize("hasAnyRole('AUDITOR','ADMIN')")
    public Result<PageResult<DataQualityIssue>> qualityIssues(
            @RequestParam(required = false) String issueType,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return Result.success(dataHubService.qualityIssues(issueType, severity, status, page, size));
    }

    @Operation(summary = "指标口径字典",
            description = "**访问级别：ROLE:AUDITOR / ROLE:ADMIN**。每个指标的表来源、过滤条件与计算公式。")
    @GetMapping("/metric-dictionary")
    @PreAuthorize("hasAnyRole('AUDITOR','ADMIN')")
    public Result<List<Map<String, String>>> metricDictionary() {
        return Result.success(dataHubService.metricDictionary());
    }

    @Operation(summary = "手动触发指标 ETL",
            description = "**访问级别：ROLE:ADMIN**。重算今天与昨天（可选 `days` 回补更多天）的日指标与科目覆盖，并登记 METRICS_ETL 流水。")
    @PostMapping("/run-etl")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<DataMetricsEtlService.EtlResult> runEtl(
            @RequestParam(required = false) Integer days) {
        // 方法级鉴权已限定 ADMIN；此处只取操作人写流水来源，取不到时回落 system
        Long uid = SecurityUtils.currentUserIdOrNull();
        String operator = uid == null ? "system" : String.valueOf(uid);
        if (days == null || days <= 1) {
            return Result.success("指标已刷新",
                    metricsEtlService.runDefault("manual:admin:" + operator));
        }
        int n = Math.min(31, days);
        List<LocalDate> dates = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            dates.add(LocalDate.now().minusDays(i));
        }
        return Result.success("指标已刷新",
                metricsEtlService.run(dates, "manual:admin:" + operator));
    }

    @Operation(summary = "手动触发质量巡检",
            description = "**访问级别：ROLE:ADMIN**。6 类检查幂等写入 data_quality_issue，已修复项自动置 RESOLVED。")
    @PostMapping("/run-quality-scan")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<DataQualityService.ScanResult> runQualityScan() {
        return Result.success("巡检完成", qualityService.scan());
    }
}
