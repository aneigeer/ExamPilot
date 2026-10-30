package com.examprep.controller;

import com.examprep.common.Result;
import com.examprep.dto.ai.AskRequest;
import com.examprep.dto.ai.AskVO;
import com.examprep.dto.ai.CapabilityVO;
import com.examprep.dto.ai.KnowledgeWeakVO;
import com.examprep.dto.ai.RecommendationVO;
import com.examprep.security.SecurityUtils;
import com.examprep.service.ai.AiQaService;
import com.examprep.service.ai.KnowledgeProfileService;
import com.examprep.service.ai.RecommendationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AI 导学接口
 *
 * <p><b>鉴权分层</b>：
 * <ul>
 *   <li>{@code GET /ai/capability} —— PUBLIC（App 启动时探测，未登录也要能调）</li>
 *   <li>{@code POST /ai/ask} —— <b>需登录（AUTH）</b>；游客不可调用，匿名请求直接 401</li>
 *   <li>{@code GET /ai/knowledge-profile} / {@code GET /ai/recommendations} —— 需登录（个人画像）</li>
 * </ul>
 *
 * <p>白名单配置见 {@code SecurityConfig}。
 */
@Tag(name = "12. AI 导学", description = "AI 问答、能力探测、学情画像、个性化推荐")
@RestController
@RequiredArgsConstructor
public class AiController {

    private final AiQaService aiQaService;
    private final KnowledgeProfileService profileService;
    private final RecommendationService recommendationService;

    @Operation(summary = "AI 问答",
            description = """
                    **访问级别：AUTH**（登录后可用；**游客不可调用**，不带 token 直接 401）。

                    基于站内已审核语料的检索增强问答（RAG）：
                    - `sources[]` 为**强制溯源**，App 端可点击跳转到对应题目 / 资料
                    - `generator` 如实标注生成器：`dashscope` / `zhipu` / `extractive` / `none`
                    - `degraded=true` 有**两种**来源，必须结合 `generator` 判读：
                      ① L3 溯源校验裁掉了「正文未引用的来源」（`generator` 仍为模型名）；
                      ② L3 判幻觉后切抽取式（`generator=extractive`）。
                      客户端不要一律当成「本地抽取」。
                    - 检索无命中时**正确拒答**（`refused=true`，`answer="未找到相关资料"`，`sources=[]`）
                    """)
    @PostMapping("/ai/ask")
    public Result<AskVO> ask(@Valid @RequestBody AskRequest request) {
        // /ai/ask 是 AUTH：匿名请求在上面的 SecurityConfig 里就被 401 拦掉，走不到这里。
        // 这里仍用 currentUserIdOrNull() 是为了兼容「已登录但尚未建立画像」的用户，
        // 不是给游客开口子（曾经注释写成「游客可提问」，与实现不符，已纠正）。
        Long userId = SecurityUtils.currentUserIdOrNull();
        return Result.success(aiQaService.ask(request, userId));
    }

    @Operation(summary = "AI 能力探测",
            description = """
                    **访问级别：PUBLIC**。App 启动时调用，决定是否显示 AI 入口。

                    - `available`：AI 问答是否可用（**索引有语料即为 true**，无大模型 Key 时走抽取式）
                    - `generator`：当前生效生成器，无 Key 时为 `extractive`
                    - `degraded` / `degradedReason`：是否降级及原因
                    - `circuitOpen`：大模型连续失败 3 次触发熔断，持续 60 秒
                    """)
    @GetMapping("/ai/capability")
    public Result<CapabilityVO> capability() {
        return Result.success(aiQaService.capability());
    }

    @Operation(summary = "薄弱知识点 Top-N",
            description = """
                    **访问级别：AUTH**。返回按 `weakness` **降序**排列的薄弱知识点。

                    算法（设计方案 C.2 七步）：单次得分 → 时间衰减（30 天半衰期）→ 难度权重
                    → 错题本加权 → 加权掌握度 → **薄弱度 = (1 - mastery) × 置信度** → Top-N。
                    其中 `confidence = MIN(1, attempts / 3)`，避免「只做 1 道错题就判薄弱度 100%」。
                    """)
    @GetMapping("/ai/knowledge-profile")
    public Result<List<KnowledgeWeakVO>> profile(
            @Parameter(description = "返回条数，默认 5，最大 20")
            @RequestParam(defaultValue = "5") int limit,
            @Parameter(description = "科目 ID，不传查全部科目")
            @RequestParam(required = false) Long subjectId) {
        Long userId = SecurityUtils.requireUserId();
        int safeLimit = Math.max(1, Math.min(20, limit));
        return Result.success(profileService.weakKnowledge(userId, safeLimit, subjectId));
    }

    @Operation(summary = "个性化推荐",
            description = """
                    **访问级别：AUTH**。错题 → 知识点 → 相关题目 / 资料的个性化推送。

                    - 每条推荐**必带 `reason`**（推荐理由不可见等于推荐不可信）
                    - 新用户 / 有效作答不足 3 题时走冷启动兜底，`reason = "GENERAL_POPULAR"`，
                      **绝不返回空列表**
                    """)
    @GetMapping("/ai/recommendations")
    public Result<List<RecommendationVO>> recommendations(
            @Parameter(description = "返回条数，默认 5，最大 20")
            @RequestParam(defaultValue = "5") int limit) {
        Long userId = SecurityUtils.requireUserId();
        int safeLimit = Math.max(1, Math.min(20, limit));
        return Result.success(recommendationService.recommend(userId, safeLimit));
    }
}
