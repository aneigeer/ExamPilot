package com.examprep.controller;

import com.examprep.common.Result;
import com.examprep.dto.AnswerCardVO;
import com.examprep.dto.AnswerResultVO;
import com.examprep.dto.AnswerSubmitRequest;
import com.examprep.dto.PracticeQuestionVO;
import com.examprep.dto.PracticeReportVO;
import com.examprep.dto.SelfEvalRequest;
import com.examprep.dto.StartPracticeRequest;
import com.examprep.security.SecurityUtils;
import com.examprep.service.PracticeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 刷题闭环接口（全部需要登录）
 */
@Tag(name = "04. 刷题", description = "开始练习、逐题作答、判分、主观题自评、错题本")
@RestController
@RequiredArgsConstructor
public class PracticeController {

    private final PracticeService practiceService;

    @Operation(summary = "开始练习",
            description = "**访问级别：AUTH**。创建一次练习并返回第一题。练习中返回的题目**不含答案**。")
    @PostMapping("/practice/start")
    public Result<PracticeQuestionVO> start(@Valid @RequestBody StartPracticeRequest request) {
        Long userId = SecurityUtils.requireUserId();
        return Result.success(practiceService.start(userId, request));
    }

    @Operation(summary = "取下一题",
            description = "**访问级别：AUTH**。`finished=true` 表示本次练习已全部答完。")
    @GetMapping("/practice/{practiceId}/next")
    public Result<PracticeQuestionVO> next(
            @Parameter(description = "练习 ID") @PathVariable Long practiceId) {
        Long userId = SecurityUtils.requireUserId();
        return Result.success(practiceService.nextQuestion(userId, practiceId));
    }

    @Operation(summary = "提交答案",
            description = """
                    **访问级别：AUTH**。

                    - 客观题（单选/多选/判断/填空）：后端**立即判分**，返回 `judged=true`、`correct`、`correctAnswer`、`analysis`
                    - 主观题（简答等）：不判分，返回 `judged=false`、`needSelfEval=true` 以及参考答案与评分要点

                    答案格式：单选 `C`；多选 `A,C`；填空多空用竖线 `答案1|答案2`；简答自由文本。
                    """)
    @PostMapping("/practice/{practiceId}/answer")
    public Result<AnswerResultVO> answer(
            @Parameter(description = "练习 ID") @PathVariable Long practiceId,
            @Valid @RequestBody AnswerSubmitRequest request) {
        Long userId = SecurityUtils.requireUserId();
        return Result.success(practiceService.submitAnswer(userId, practiceId, request));
    }

    @Operation(summary = "主观题自评",
            description = """
                    **访问级别：AUTH**。自评结果折算规则：

                    - `KNOW` 会 → 记满分，计入正确数
                    - `UNSURE` 模糊 → 记半分，不计入正确数
                    - `DONT_KNOW` 不会 → 记 0 分，**自动收入错题本**
                    """)
    @PostMapping("/practice/{practiceId}/self-eval")
    public Result<AnswerResultVO> selfEval(
            @Parameter(description = "练习 ID") @PathVariable Long practiceId,
            @Valid @RequestBody SelfEvalRequest request) {
        Long userId = SecurityUtils.requireUserId();
        return Result.success(practiceService.selfEval(userId, practiceId, request));
    }

    @Operation(summary = "结束练习并出报告", description = "**访问级别：AUTH**")
    @PostMapping("/practice/{practiceId}/finish")
    public Result<PracticeReportVO> finish(
            @Parameter(description = "练习 ID") @PathVariable Long practiceId) {
        Long userId = SecurityUtils.requireUserId();
        return Result.success(practiceService.finish(userId, practiceId));
    }

    @Operation(summary = "练习答题卡",
            description = "**访问级别：AUTH**。返回本次练习每道题的状态（未答/正确/错误/自评/当前待答），App 端渲染答题卡网格用。")
    @GetMapping("/practice/{practiceId}/card")
    public Result<AnswerCardVO> answerCard(
            @Parameter(description = "练习 ID") @PathVariable Long practiceId) {
        Long userId = SecurityUtils.requireUserId();
        return Result.success(practiceService.getAnswerCard(userId, practiceId));
    }

    @Operation(summary = "练习报告", description = "**访问级别：AUTH**。含正确率与逐题明细。")
    @GetMapping("/practice/{practiceId}/report")
    public Result<PracticeReportVO> report(
            @Parameter(description = "练习 ID") @PathVariable Long practiceId) {
        Long userId = SecurityUtils.requireUserId();
        return Result.success(practiceService.getReport(userId, practiceId));
    }

    @Operation(summary = "错题本列表", description = "**访问级别：AUTH**。仅返回未掌握的错题。")
    @GetMapping("/wrong-book")
    public Result<List<PracticeReportVO.Item>> wrongBook() {
        Long userId = SecurityUtils.requireUserId();
        return Result.success(practiceService.listWrongBook(userId));
    }

    @Operation(summary = "移出错题本", description = "**访问级别：AUTH**")
    @DeleteMapping("/wrong-book/{questionId}")
    public Result<Void> removeWrongBook(
            @Parameter(description = "题目 ID") @PathVariable Long questionId) {
        Long userId = SecurityUtils.requireUserId();
        practiceService.removeFromWrongBook(userId, questionId);
        return Result.success("已移出错题本", null);
    }
}
