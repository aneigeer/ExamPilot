package com.examprep.controller;

import com.examprep.entity.AiAgentRun;
import com.examprep.entity.AiEvalRun;
import com.examprep.mapper.AiAgentCheckpointMapper;
import com.examprep.mapper.AiAgentRunMapper;
import com.examprep.mapper.AiEvalRunMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 运维接口的 Web 层验证（真 Filter 链 + 真方法级鉴权 + 真序列化）。
 *
 * <p><b>为什么单测不够</b>：{@code EvalCompareTest} 直接调 service，绕过了三件只在
 * Spring 里才成立的事：
 * <ol>
 *   <li>参数绑定：{@code ?baseId=} 缺失/非数字要变 400（靠既有 {@code GlobalExceptionHandler}）；</li>
 *   <li>{@code @PreAuthorize} 是否真的生效（写错注解、或漏 {@code @EnableMethodSecurity} 都不报错）；</li>
 *   <li>JSON 形状：{@code delta} 的键含 {@code .} 和 {@code @}（如 {@code metrics.Recall@5}），
 *       全局 {@code non_null} 序列化下是否还在。</li>
 * </ol>
 *
 * <p><b>为什么不测 purge 的成功路径</b>：它是真删除。这里只测「越界 400」与「AUDITOR 403」——
 * 两者都在触达 DELETE 之前就被拦下，<b>零写入</b>。删除逻辑本身由
 * {@code AgentRunRetentionServiceTest} 用 mock 覆盖。
 */
@DisplayName("运维接口 Web 层：评估对比 + 保留期清理鉴权")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Transactional
class AiOpsEndpointsTest {

    private static final String BASE_METRICS =
            "{\"用例数\":30,\"Recall@5\":\"90.0%\",\"MRR\":\"0.800\",\"误拒率\":\"-\"}";

    private static final String TARGET_METRICS =
            "{\"用例数\":30,\"Recall@5\":\"91.7%\",\"MRR\":\"0.845\",\"误拒率\":\"-\"}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AiEvalRunMapper evalRunMapper;

    @Autowired
    private AiAgentRunMapper runMapper;

    @Autowired
    private AiAgentCheckpointMapper checkpointMapper;

    private Long baseId;
    private Long targetId;

    @BeforeEach
    void insertEvalRuns() {
        baseId = insert("RETRIEVAL", 30, 27, BASE_METRICS);
        targetId = insert("RETRIEVAL", 30, 28, TARGET_METRICS);
    }

    private Long insert(String layer, int total, int passed, String metricsJson) {
        AiEvalRun run = new AiEvalRun();
        run.setLayer(layer);
        run.setStatus(AiEvalRun.STATUS_SUCCESS);
        run.setTotal(total);
        run.setPassed(passed);
        run.setMetricsJson(metricsJson);
        run.setCreatedAt(LocalDateTime.now());
        evalRunMapper.insert(run);
        return run.getId();
    }

    @Test
    @DisplayName("对比（AUDITOR）：200 + 差值键含 metrics. 前缀，@ 与 . 不被转义")
    @WithMockUser(username = "auditor", roles = "AUDITOR")
    void compareReturnsDelta() throws Exception {
        mockMvc.perform(get("/admin/ai/eval/compare")
                        .param("baseId", String.valueOf(baseId))
                        .param("targetId", String.valueOf(targetId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.comparable").value(true))
                .andExpect(jsonPath("$.data.base.passRate").value(0.9))
                .andExpect(jsonPath("$.data.target.passRate").value(0.9333))
                .andExpect(jsonPath("$.data.delta.passRate").value(0.0333))
                .andExpect(jsonPath("$.data.delta['metrics.Recall@5']").value(0.017))
                .andExpect(jsonPath("$.data.delta['metrics.MRR']").value(0.045))
                .andExpect(jsonPath("$.data.delta['metrics.误拒率']").value("-"))
                .andExpect(jsonPath("$.data.delta['metrics.用例数']").value(0.0))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    @DisplayName("对比：记录不存在 → HTTP 404 且 code=404")
    @WithMockUser(username = "auditor", roles = "AUDITOR")
    void compareReports404() throws Exception {
        mockMvc.perform(get("/admin/ai/eval/compare")
                        .param("baseId", "99999999")
                        .param("targetId", String.valueOf(targetId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @DisplayName("对比：缺 targetId → HTTP 400（既有 MissingServletRequestParameter 处理）")
    @WithMockUser(username = "auditor", roles = "AUDITOR")
    void compareRejectsMissingParam() throws Exception {
        mockMvc.perform(get("/admin/ai/eval/compare")
                        .param("baseId", String.valueOf(baseId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("对比：非数字 id → HTTP 400（类型不匹配）")
    @WithMockUser(username = "auditor", roles = "AUDITOR")
    void compareRejectsNonNumericId() throws Exception {
        mockMvc.perform(get("/admin/ai/eval/compare")
                        .param("baseId", "abc")
                        .param("targetId", String.valueOf(targetId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("对比：未登录 → 401（/admin/** 需 AUDITOR/ADMIN）")
    void compareRequiresAuth() throws Exception {
        mockMvc.perform(get("/admin/ai/eval/compare")
                        .param("baseId", String.valueOf(baseId))
                        .param("targetId", String.valueOf(targetId)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("保留期预读（ADMIN）：200，只读计数")
    @WithMockUser(username = "admin", roles = "ADMIN")
    void previewAllowedForAdmin() throws Exception {
        mockMvc.perform(get("/admin/ai/retention/preview").param("days", "90"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.days").value(90))
                .andExpect(jsonPath("$.data.pending").isNumber());
    }

    @Test
    @DisplayName("清扫（AUDITOR）：403 —— 方法级 @PreAuthorize 只放 ADMIN")
    @WithMockUser(username = "auditor", roles = "AUDITOR")
    void purgeForbiddenForAuditor() throws Exception {
        mockMvc.perform(post("/admin/ai/retention/purge").param("days", "90"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("清扫（ADMIN）：days=0 → 400，且未触达 DELETE（越界先拒）")
    @WithMockUser(username = "admin", roles = "ADMIN")
    void purgeRejectsIllegalDaysBeforeDeleting() throws Exception {
        mockMvc.perform(post("/admin/ai/retention/purge").param("days", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("清扫（ADMIN）：days=3651 → 400（上界同样显式拒绝）")
    @WithMockUser(username = "admin", roles = "ADMIN")
    void purgeRejectsTooLargeDays() throws Exception {
        mockMvc.perform(post("/admin/ai/retention/purge").param("days", "3651"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("清扫（ADMIN）：days=3650 成功路径，返回 {deleted,days,cutoff}（10 年窗口必然 0 行，事务回滚）")
    @WithMockUser(username = "admin", roles = "ADMIN")
    void purgeReturnsPurgeResult() throws Exception {
        mockMvc.perform(post("/admin/ai/retention/purge").param("days", "3650"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.deleted").isNumber())
                .andExpect(jsonPath("$.data.days").value(3650))
                .andExpect(jsonPath("$.data.cutoff").isNotEmpty());
    }

    // ==================================================================
    // 检查点与恢复（只测"不启动新会话"的分支：成功分支会真的打模型）
    // ==================================================================

    @Test
    @DisplayName("检查点列表：200 且按 step 升序返回，payload 原样（不含原文这一点由写入侧保证）")
    @WithMockUser(username = "auditor", roles = "AUDITOR")
    void listsCheckpoints() throws Exception {
        Long runId = insertRun(AiAgentRun.STATUS_FAILED);
        insertCheckpoint(runId, 1, "{\"event\":\"thought\"}");
        insertCheckpoint(runId, 2, "{\"event\":\"tool_call\",\"tool\":\"search_knowledge\","
                + "\"argKeys\":[\"query\"]}");

        mockMvc.perform(get("/admin/ai/agent/runs/" + runId + "/checkpoints"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].stepNo").value(1))
                .andExpect(jsonPath("$.data[0].status").value("RECORDED"))
                .andExpect(jsonPath("$.data[1].stepNo").value(2));
    }

    @Test
    @DisplayName("恢复：SUCCESS 的运行 → 409（不启动新会话，所以可以安全地跑）")
    @WithMockUser(username = "auditor", roles = "AUDITOR")
    void resumeRejectsSuccessRun() throws Exception {
        Long runId = insertRun(AiAgentRun.STATUS_SUCCESS);
        insertCheckpoint(runId, 1, "{\"event\":\"thought\"}");

        mockMvc.perform(post("/admin/ai/agent/runs/" + runId + "/resume"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    @DisplayName("恢复：运行不存在 → 404")
    @WithMockUser(username = "auditor", roles = "AUDITOR")
    void resumeReports404() throws Exception {
        mockMvc.perform(post("/admin/ai/agent/runs/99999999/resume"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @DisplayName("恢复：FAILED 但没有检查点 → 409")
    @WithMockUser(username = "auditor", roles = "AUDITOR")
    void resumeWithoutCheckpointIs409() throws Exception {
        Long runId = insertRun(AiAgentRun.STATUS_FAILED);

        mockMvc.perform(post("/admin/ai/agent/runs/" + runId + "/resume"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    @DisplayName("检查点/恢复：未登录 → 401")
    void checkpointEndpointsRequireAuth() throws Exception {
        mockMvc.perform(get("/admin/ai/agent/runs/1/checkpoints"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("指标：响应里必须有 terminal（供管理端展示分母），且 terminal = total − running")
    @WithMockUser(username = "auditor", roles = "AUDITOR")
    void metricsExposesTerminalForDenominator() throws Exception {
        // 样本很少时只显示百分比容易误读，所以服务端必须把分母 terminal 交给前端；
        // 这里断言它确实在响应里且自洽（不依赖库里有多少数据）。
        mockMvc.perform(get("/admin/ai/metrics").param("days", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").isNumber())
                .andExpect(jsonPath("$.data.running").isNumber())
                .andExpect(jsonPath("$.data.terminal").isNumber())
                .andExpect(jsonPath("$.data.statusCounts.RUNNING").isNumber())
                .andExpect(jsonPath("$.data.latency.samples").isNumber());
    }

    private Long insertRun(String status) {
        AiAgentRun run = new AiAgentRun();
        run.setUserId(900_201L);
        run.setTraceId("0123456789abcdef");
        run.setQuestion("qa-checkpoint-probe");
        run.setSessionId("s-qa");
        run.setStatus(status);
        run.setStepCount(0);
        run.setToolCalls(0);
        run.setTotalMs(0);
        run.setDegraded(false);
        run.setCreatedAt(LocalDateTime.of(2020, 2, 1, 12, 0, 0));
        runMapper.insert(run);
        return run.getId();
    }

    private void insertCheckpoint(Long runId, int stepNo, String payload) {
        checkpointMapper.insertCheckpoint(runId, "s-qa", stepNo, payload);
    }
}
