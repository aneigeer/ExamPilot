package com.examprep.controller;

import com.examprep.common.Result;
import com.examprep.service.ai.ChunkSyncService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * AI 索引管理接口（管理端）
 *
 * <p>首次使用前点一次「重建索引」即可，30 秒内完成。
 * <b>幂等</b>：{@code content_hash} 未变化时跳过，重复调用 {@code skipped} 全部命中。
 */
@Tag(name = "13. AI 索引管理", description = "语料索引重建（管理端）")
@RestController
@RequestMapping("/admin/ai")
@RequiredArgsConstructor
public class AiAdminController {

    private final ChunkSyncService chunkSyncService;

    @Operation(summary = "全量重建 AI 索引",
            description = """
                    **访问级别：ROLE:AUDITOR / ROLE:ADMIN**。

                    从已审核（APPROVED）的题目 / 资料 / 知识点重新抽取语料并写入 `ai_chunk`。
                    - 客观题答案**不入索引**（防泄题），只取主观题参考答案
                    - 幂等：`content_hash` 未变则跳过，重复调用 `skipped` 命中、`chunk` 不变
                    - 来源下架的块置 `status=INVALID`，**不物理删除**，保留可追溯性
                    - 若 FastAPI 通道开启，会顺带异步推送向量索引（失败不影响本次返回）
                    """)
    @PostMapping("/reindex")
    @PreAuthorize("hasAnyRole('AUDITOR','ADMIN')")
    public Result<Map<String, Object>> reindex() {
        return Result.success("索引重建完成", chunkSyncService.reindexAll());
    }

    @Operation(summary = "推送待向量化的语料到 FastAPI",
            description = """
                    **访问级别：ROLE:AUDITOR / ROLE:ADMIN**。

                    把 `ai_chunk` 中 `vector_synced <> 1` 的块推送到 FastAPI → Qdrant。
                    - **异步 + 状态位**：推送失败只留 `vector_synced=0`，不影响任何业务接口
                    - 幂等：Python 侧按 `content_hash` 去重，重复推送不会产生重复向量
                    - FastAPI 不可用时返回 `pushed=false` 并说明原因，**不抛异常**
                    """)
    @PostMapping("/vector/sync")
    @PreAuthorize("hasAnyRole('AUDITOR','ADMIN')")
    public Result<Map<String, Object>> vectorSync(
            @Parameter(description = "单次最多推送条数，最大 500")
            @RequestParam(defaultValue = "200") int limit) {
        int safeLimit = Math.max(1, Math.min(500, limit));
        return Result.success("向量推送完成", chunkSyncService.pushVectorIndex(safeLimit));
    }

    @Operation(summary = "向量索引对账（MySQL ↔ Qdrant）",
            description = """
                    **访问级别：ROLE:AUDITOR / ROLE:ADMIN**。

                    核心一致性断言：**MySQL 已向量化数 应等于 Qdrant points 数**。
                    不一致时自动补推待同步语料；`model_id` 不匹配（换过 embedding 模型）
                    会明确提示需全量重建——**绝不允许同一 collection 混用不同模型的向量**。
                    """)
    @GetMapping("/vector/reconcile")
    @PreAuthorize("hasAnyRole('AUDITOR','ADMIN')")
    public Result<Map<String, Object>> vectorReconcile() {
        return Result.success(chunkSyncService.reconcileVectorIndex());
    }

    @Operation(summary = "向量索引对账并补推（POST 版）",
            description = """
                    **访问级别：ROLE:AUDITOR / ROLE:ADMIN**。

                    与 GET 版同逻辑，但可作为**写操作**显式触发（GET 不带副作用更合适，
                    但使用/排障场景需要一条能反复点的命令）。

                    ★ 典型用法：若日志出现
                    「向量推送未执行（FastAPI 处于熔断状态…），42 条待同步语料保持 vector_synced=0」，
                    说明**数据其实可能已在 Qdrant 里**（此前推送成功过），只是那次状态位没回写。
                    等熔断窗口过后调本接口即可自动补齐，无需全量 reindex。
                    """)
    @PostMapping("/vector/reconcile")
    @PreAuthorize("hasAnyRole('AUDITOR','ADMIN')")
    public Result<Map<String, Object>> vectorReconcileAndRepair() {
        return Result.success("向量对账完成", chunkSyncService.reconcileVectorIndex());
    }

    @Operation(summary = "全量同步向量索引（可从假状态位恢复）",
            description = """
                    **访问级别：ROLE:AUDITOR / ROLE:ADMIN**。

                    ★ 与 `/vector/sync` 的关键区别：本接口会**先把 `vector_synced` 重置为 0**。
                    因为待推送的筛选条件是 `vector_synced <> 1` —— 一旦出现
                    「状态位写成功但向量实际没入库」的假标记，那些块会被永久跳过，
                    推多少次都推不上去。必须重置才推得动。

                    - `sourceType` 可选：只推某一类型（如先推 `QUESTION`，课件等检索过滤就绪后再推）
                    - 分批 200 条，逐批推进，单批失败不中断（保持待同步，可再次调用续推）
                    - `ok=true` 表示已无待同步；否则再调一次即可续推
                    """)
    @PostMapping("/vector/full-sync")
    @PreAuthorize("hasAnyRole('AUDITOR','ADMIN')")
    public Result<Map<String, Object>> vectorFullSync(
            @Parameter(description = "只同步该来源类型，如 QUESTION / COURSEWARE；留空为全部")
            @RequestParam(required = false) String sourceType,
            @Parameter(description = "最多推多少批（每批 200 条），防止单次请求过久")
            @RequestParam(defaultValue = "60") int maxBatches) {
        return Result.success("全量向量同步完成",
                chunkSyncService.fullVectorSync(sourceType, maxBatches));
    }
}
