package com.examprep.controller;

import com.examprep.common.Result;
import com.examprep.dto.AccountExportVO;
import com.examprep.dto.DeactivateRequest;
import com.examprep.dto.MasteryVO;
import com.examprep.dto.MyStatsVO;
import com.examprep.dto.PracticeTrendVO;
import com.examprep.dto.ai.AgentMemoryRequest;
import com.examprep.dto.ai.AgentMemoryVO;
import com.examprep.dto.ai.AiStudyPlanRequest;
import com.examprep.dto.ai.AiStudyPlanVO;
import com.examprep.dto.ai.StudyPlanVO;
import com.examprep.dto.ai.StudyTargetRequest;
import com.examprep.security.SecurityUtils;
import com.examprep.service.AccountService;
import com.examprep.service.MyStatsService;
import com.examprep.service.StudyPlanService;
import com.examprep.service.ai.AiStudyPlanService;
import com.examprep.service.ai.agent.AgentMemoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
    private final AiStudyPlanService aiStudyPlanService;
    private final AgentMemoryService memoryService;

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

    @Operation(summary = "根据用户错题 AI 制定学习计划",
            description = """
                    **访问级别：AUTH**。读取本人未掌握错题（`wrong_book`）与学情画像，
                    由大模型生成分日提分计划；零错题时返回冷启动引导，大模型不可用时静默降级为规则引擎计划。
                    """)
    @PostMapping("/study-plan/ai")
    public Result<AiStudyPlanVO> aiStudyPlan(
            @Valid @RequestBody(required = false) AiStudyPlanRequest request) {
        Long userId = SecurityUtils.requireUserId();
        return Result.success(aiStudyPlanService.generate(userId, request));
    }

    // ==================================================================
    // Agent 长期记忆（学生可见、可改、可删）
    // ==================================================================

    @Operation(summary = "我的 Agent 长期记忆",
            description = """
                    **访问级别：AUTH**。只返回本人的记忆，按更新时间倒序。

                    长期记忆是学生主动维护的稳定设定（目标考试、薄弱模块等），
                    每轮对话会以「【学生长期记忆】key: value」注入模型上下文，
                    **改动下一轮立即生效**（不做缓存）。
                    """)
    @GetMapping("/agent-memory")
    public Result<java.util.List<AgentMemoryVO>> listAgentMemory() {
        Long userId = SecurityUtils.requireUserId();
        return Result.success(memoryService.list(userId));
    }

    @Operation(summary = "新增 Agent 长期记忆",
            description = """
                    **访问级别：AUTH**。

                    约束：单用户最多 20 条（超出 **409**）；记忆值 ≤200 字（超出 **400**）；
                    同一「记忆键」已存在 → **409**（请用 PUT 修改）。

                    学生删过的同键记忆再添加时会**复活原记录**（而不是新建），
                    这样「删了再加」不会踩唯一键。
                    """)
    @PostMapping("/agent-memory")
    public Result<AgentMemoryVO> createAgentMemory(@Valid @RequestBody AgentMemoryRequest request) {
        Long userId = SecurityUtils.requireUserId();
        return Result.success("已保存", memoryService.create(userId, request));
    }

    @Operation(summary = "修改 Agent 长期记忆",
            description = "**访问级别：AUTH**。只改值，**记忆键不可改**（换键等于新建）。"
                    + "非本人记忆 → **403**；记录不存在/已删除 → **404**。")
    @PutMapping("/agent-memory/{id}")
    public Result<AgentMemoryVO> updateAgentMemory(@PathVariable Long id,
                                                   @Valid @RequestBody AgentMemoryRequest request) {
        Long userId = SecurityUtils.requireUserId();
        return Result.success("已更新", memoryService.update(userId, id, request));
    }

    @Operation(summary = "删除 Agent 长期记忆",
            description = "**访问级别：AUTH**。逻辑删除（保留审计痕迹，不物理删）。"
                    + "非本人记忆 → **403**；下一轮对话不再注入该条。")
    @DeleteMapping("/agent-memory/{id}")
    public Result<Void> deleteAgentMemory(@PathVariable Long id) {
        Long userId = SecurityUtils.requireUserId();
        memoryService.delete(userId, id);
        return Result.success("已删除", null);
    }
}
