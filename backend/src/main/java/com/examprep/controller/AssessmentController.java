package com.examprep.controller;

import com.examprep.common.Result;
import com.examprep.dto.DiagnosticPaperRequest;
import com.examprep.dto.MockPaperVO;
import com.examprep.security.SecurityUtils;
import com.examprep.service.assessment.AssessmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 学生侧：双轨测评中台（诊断组卷 + 综合画像）
 */
@Tag(name = "17B. 测评（诊断组卷与画像）", description = "智能诊断卷、个人能力画像")
@RestController
@RequestMapping("/assessment")
@RequiredArgsConstructor
public class AssessmentController {

    private final AssessmentService assessmentService;

    @Operation(summary = "生成智能诊断卷",
            description = """
                    **访问级别：AUTH**。按「薄弱知识点优先 + 难度梯度 30/50/20 + 避开近 14 天已掌握题」自动组卷。

                    - 底层复用 exam_paper / exam_record，交卷判分与模拟考试一致
                    - 交卷后错题同样进入错题本，可在「我的模考历史」回看
                    """)
    @PostMapping("/diagnostic-paper")
    public Result<MockPaperVO> diagnosticPaper(@Valid @RequestBody(required = false) DiagnosticPaperRequest request) {
        Long userId = SecurityUtils.requireUserId();
        return Result.success("诊断卷已生成", assessmentService.diagnosticPaper(userId, request));
    }

    @Operation(summary = "个人综合测评画像",
            description = "**访问级别：AUTH**。近 30 天正确率、模考得分趋势、AI 面试得分、科目/知识点掌握度与薄弱 Top5。")
    @GetMapping("/profile")
    public Result<Map<String, Object>> profile() {
        Long userId = SecurityUtils.requireUserId();
        return Result.success(assessmentService.profile(userId));
    }
}
