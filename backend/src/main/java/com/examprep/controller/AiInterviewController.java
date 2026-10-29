package com.examprep.controller;

import com.examprep.common.PageResult;
import com.examprep.common.Result;
import com.examprep.dto.ai.InterviewAnswerRequest;
import com.examprep.dto.ai.InterviewDetailVO;
import com.examprep.dto.ai.InterviewHistoryVO;
import com.examprep.dto.ai.InterviewStartRequest;
import com.examprep.security.SecurityUtils;
import com.examprep.service.ai.AiInterviewService;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 面试接口（模拟面试官：出题 → 作答 → 评分 → 追问 → 报告）
 *
 * <p><b>鉴权</b>：两个接口都需要登录（{@code anyRequest().authenticated()} 覆盖），
 * 学生角色即可访问。未登录返回 401。
 *
 * <p><b>契约（与 Python AI 服务逐字一致，三层共用 snake_case）</b>：
 * <pre>
 *   POST /ai/interview/start   {topic, total, subject_id}
 *     → {session_id, topic, question_no, total, question, source:{title, source_file, page_no}}
 *
 *   POST /ai/interview/answer  {session_id, answer}
 *     → 未结束 {session_id, question_no, total, score, verdict, hit_points[], miss_points[],
 *              reference, follow_up, next_question, finished:false}
 *     → 结束   {session_id, finished:true, report:{total_score, level,
 *              per_question:[{question, score, comment}], strengths[], weaknesses[],
 *              suggestions[], degraded}}
 * </pre>
 * 响应体以 {@link JsonNode} 透传，不做字段重命名，避免「Java 少写一个字段 →
 * App 上就是不显示」这类静默丢数据的故障。
 *
 * <p><b>错误码</b>：
 * <ul>
 *   <li>{@code 503} 面试服务暂不可用，请稍后再试（Python 未启动 / 超时 / 熔断）</li>
 *   <li>{@code 404} 面试会话已过期，请重新开始（Python 侧 SESSION_NOT_FOUND）</li>
 *   <li>{@code 403} 无权操作该面试会话（他人 session_id）</li>
 *   <li>{@code 400} 参数不合规（题目数越界 / 作答为空）</li>
 * </ul>
 */
@Tag(name = "14. AI 面试", description = "模拟面试：出题、评分、追问与整场报告")
@RestController
@RequiredArgsConstructor
public class AiInterviewController {

    private final AiInterviewService interviewService;

    @Operation(summary = "开始面试",
            description = """
                    **访问级别：AUTH**（学生角色即可）。

                    - `topic` 可为空，为空时随机抽取主题
                    - `total` 默认 3，范围 1-10
                    - 返回的第一题即 `question`，出处由 `source` 给出（标题 / 文件名 / 页码）

                    Python 服务不可用时返回 503「面试服务暂不可用，请稍后再试」——
                    **面试不做本地降级**：宁可让用户稍后重试，也不给出编造的题目或评分。
                    """)
    @PostMapping("/ai/interview/start")
    public Result<JsonNode> start(@Valid @RequestBody InterviewStartRequest request) {
        Long userId = SecurityUtils.requireUserId();
        return Result.success(interviewService.start(request, userId));
    }

    @Operation(summary = "提交作答（评分 / 追问 / 下一题 / 报告）",
            description = """
                    **访问级别：AUTH**。

                    - `finished=false`：本题已评分，`follow_up` 为面试官追问（可能为空串），
                      `next_question` 为下一题，`hit_points` / `miss_points` 说明得分与失分点
                    - `finished=true`：整场结束，`report` 给出总分、等级、逐题评语与改进建议；
                      其中 `degraded=true` 表示评分由降级模型给出（仍依据语料，非编造）

                    会话不存在/已结束返回 404「面试会话已过期，请重新开始」。
                    """)
    @PostMapping("/ai/interview/answer")
    public Result<JsonNode> answer(@Valid @RequestBody InterviewAnswerRequest request) {
        Long userId = SecurityUtils.requireUserId();
        return Result.success(interviewService.answer(request, userId));
    }

    @Operation(summary = "面试历史列表",
            description = """
                    **访问级别：AUTH**。默认只返回**本人**记录；审核员/超管可传 `all=true` 查看全部。

                    `answeredCount` 是**实评题数**（含追问轮），与 `totalQuestions`（计划题数）分开：
                    语料耗尽时会提前收尾，两者可能不同（如计划 5 题、实评 3 题）。
                    """)
    @GetMapping("/ai/interview/history")
    public Result<PageResult<InterviewHistoryVO>> history(
            @Parameter(description = "页码，从 1 开始") @RequestParam(defaultValue = "1") long page,
            @Parameter(description = "每页条数，最大 50") @RequestParam(defaultValue = "10") long size,
            @Parameter(description = "审核员/超管可传 true 查看全部")
            @RequestParam(defaultValue = "false") boolean all) {
        Long userId = SecurityUtils.requireUserId();
        Long filter = (all && SecurityUtils.isAuditorOrAdmin()) ? null : userId;
        return Result.success(interviewService.history(page, size, filter));
    }

    @Operation(summary = "面试详情（复盘）",
            description = """
                    **访问级别：AUTH**。本人记录可看；审核员/超管可看任意记录。

                    `qaList` 按题号升序、同题追问紧随主作答，客户端按顺序渲染即为完整对话。
                    """)
    @GetMapping("/ai/interview/history/{id}")
    public Result<InterviewDetailVO> detail(@PathVariable Long id) {
        Long userId = SecurityUtils.requireUserId();
        return Result.success(interviewService.detail(id, userId, SecurityUtils.isAuditorOrAdmin()));
    }
}
