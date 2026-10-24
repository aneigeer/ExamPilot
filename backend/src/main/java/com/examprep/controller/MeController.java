package com.examprep.controller;

import com.examprep.common.Result;
import com.examprep.dto.AccountExportVO;
import com.examprep.dto.DeactivateRequest;
import com.examprep.dto.MasteryVO;
import com.examprep.dto.MyStatsVO;
import com.examprep.dto.PracticeTrendVO;
import com.examprep.dto.ai.StudyPlanVO;
import com.examprep.dto.ai.StudyTargetRequest;
import com.examprep.security.SecurityUtils;
import com.examprep.service.AccountService;
import com.examprep.service.MyStatsService;
import com.examprep.service.StudyPlanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 「我的」页面接口
 */
@Tag(name = "16. 我的", description = "我的页面统计")
@RestController
@RequestMapping("/me")
@RequiredArgsConstructor
public class MeController {

    private final MyStatsService myStatsService;
    private final AccountService accountService;
    private final StudyPlanService studyPlanService;

    @Operation(summary = "我的统计",
            description = "**访问级别：AUTH**。App「我的」页顶部统计用：练习次数、正确率、错题数、收藏数、下载数、上传数、未读通知。")
    @GetMapping("/stats")
    public Result<MyStatsVO> stats() {
        Long userId = SecurityUtils.requireUserId();
        return Result.success(myStatsService.stats(userId));
    }

    @Operation(summary = "知识点掌握度",
            description = "**访问级别：AUTH**。按知识点聚合已刷题数与正确率（P0-A「简单统计」要求项）。`subjectId` 不传查全部科目。")
    @GetMapping("/mastery")
    public Result<MasteryVO> mastery(
            @io.swagger.v3.oas.annotations.Parameter(description = "科目 ID，不传查全部")
            @RequestParam(required = false) Long subjectId) {
        Long userId = SecurityUtils.requireUserId();
        return Result.success(myStatsService.mastery(userId, subjectId));
    }

    @Operation(summary = "刷题趋势",
            description = "**访问级别：AUTH**。近 N 天逐日答题数与正确数（无刷题的日期补 0），前端渲染趋势图用。")
    @GetMapping("/practice-trend")
    public Result<PracticeTrendVO> trend(
            @io.swagger.v3.oas.annotations.Parameter(description = "统计天数，默认 7，最大 90")
            @RequestParam(defaultValue = "7") Integer days) {
        Long userId = SecurityUtils.requireUserId();
        return Result.success(myStatsService.trend(userId, days));
    }

    @Operation(summary = "导出个人数据",
            description = "**访问级别：AUTH**。一次性导出本人账号资料、答题统计、错题本、练习记录、收藏、"
                    + "下载记录与题目笔记（不含密码等凭证），供用户自行留存（个人信息可携带权）。")
    @GetMapping("/export")
    public Result<AccountExportVO> export() {
        Long userId = SecurityUtils.requireUserId();
        return Result.success("导出成功", accountService.export(userId));
    }

    @Operation(summary = "注销账号",
            description = "**访问级别：AUTH**。需二次校验密码，并将确认语 `confirmText` 原样填写为「注销账号」。"
                    + "注销后个人标识即刻匿名化（学号改为 deleted_<id>、清除姓名、密码随机化），账号不可再登录；"
                    + "去标识化的历史答题流水予以保留用于统计。此操作不可撤销。")
    @PostMapping("/deactivate")
    public Result<Void> deactivate(@Valid @RequestBody DeactivateRequest request) {
        Long userId = SecurityUtils.requireUserId();
        accountService.deactivate(userId, request);
        return Result.success("账号已注销，感谢使用", null);
    }

    @Operation(summary = "今日学习计划",
            description = """
                    **访问级别：AUTH**。

                    按规则引擎生成今日任务，**按需计算、不落库**；完成度从真实作答派生，
                    因此不会出现「计划表说完成了、实际没做题」。

                    三条规则（按优先级）：
                    1. **复习优先**——今天到期的错题必须排在前面（间隔重复不等人）；
                    2. **前置基础**——薄弱知识点的父知识点也薄弱时，先补父知识点；
                    3. **薄弱攻坚**——剩余配额按掌握度升序分配，掌握度越低分得越多。

                    每条任务都带 `reason`（为什么给你排这个），可解释、可质疑。
                    """)
    @GetMapping("/study-plan")
    public Result<StudyPlanVO> studyPlan() {
        Long userId = SecurityUtils.requireUserId();
        return Result.success(studyPlanService.today(userId));
    }

    @Operation(summary = "设定学习目标",
            description = "**访问级别：AUTH**。设定每日目标题量与目标科目（`subjectId` 不传表示不限），"
                    + "返回生效后的今日计划。题量超出 [5,100] 会被**钳制**而不是报错。")
    @PutMapping("/study-plan/target")
    public Result<StudyPlanVO> setStudyTarget(@Valid @RequestBody StudyTargetRequest request) {
        Long userId = SecurityUtils.requireUserId();
        return Result.success("目标已更新",
                studyPlanService.setTarget(userId, request.getDailyTarget(), request.getSubjectId()));
    }
}
