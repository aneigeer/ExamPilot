package com.examprep.service.ai;

import com.examprep.entity.AiChunk;
import com.examprep.mapper.AiChunkMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 孤儿向量清理测试
 *
 * <p><b>核心命题</b>：块被置 {@code INVALID} 后，Qdrant 里的向量点必须<b>真的被删掉</b>。
 * 否则 payload 里过期的 {@code status=ACTIVE} 快照会让检索的
 * {@code status='ACTIVE'} 过滤放行孤儿，孤儿占掉 top-K 名额、把有效结果挤出去
 * （实测 4 组查询各取 top-20，80 个命中里 32 个是已失效块）。
 *
 * <p>同时验证<b>降级安全</b>：FastAPI 不可用时只保留状态位、绝不影响主业务。
 */
@DisplayName("孤儿向量清理：INVALID 块必须在 Qdrant 侧也删掉")
class ChunkSyncServiceTest {

    private static final int BATCH = 500;

    private record Fixture(ChunkSyncService service, AiChunkMapper mapper,
                           FastApiClient client, AiProperties props) {
    }

    private Fixture fixture(boolean fastapiEnabled) {
        AiChunkMapper mapper = mock(AiChunkMapper.class);
        FastApiClient client = mock(FastApiClient.class);
        AiProperties props = new AiProperties();
        props.getFastapi().setEnabled(fastapiEnabled);
        props.getVector().setEnabled(fastapiEnabled);
        ChunkSyncService service = new ChunkSyncService(
                mapper, mock(ChunkExtractor.class), props, client);
        return new Fixture(service, mapper, client, props);
    }

    @Test
    @DisplayName("① 按批删除孤儿向量，并把块标为 VECTOR_PURGED（下一次重建不再选到）")
    void 按批删除并标记已移除() {
        Fixture f = fixture(true);
        when(f.mapper().selectOrphanVectorIds(BATCH))
                .thenReturn(List.of(11L, 12L), List.of(13L), List.of());
        when(f.client().deleteIndex(anyList()))
                .thenReturn(new FastApiClient.IndexDeleteResult(2, 2),
                        new FastApiClient.IndexDeleteResult(1, 1));

        Map<String, Object> stats = f.service().purgeOrphanVectors();

        assertEquals(3, stats.get("purged"));
        assertEquals(2, stats.get("batches"));
        verify(f.mapper()).markVectorSynced(List.of(11L, 12L), AiChunk.VECTOR_PURGED);
        verify(f.mapper()).markVectorSynced(List.of(13L), AiChunk.VECTOR_PURGED);
    }

    @Test
    @DisplayName("② 删除失败：不标记状态位（保留 vector_synced=1，下次重建自动重试）")
    void 删除失败时保留状态位() {
        Fixture f = fixture(true);
        when(f.mapper().selectOrphanVectorIds(BATCH)).thenReturn(List.of(11L));
        when(f.client().deleteIndex(anyList())).thenReturn(null);
        when(f.client().unavailableReason()).thenReturn("FastAPI 处于熔断状态");

        Map<String, Object> stats = f.service().purgeOrphanVectors();

        assertEquals(0, stats.get("purged"));
        assertTrue(String.valueOf(stats.get("reason")).contains("熔断"),
                "必须把真实原因带出来，否则排障无从下手");
        verify(f.mapper(), never()).markVectorSynced(anyList(), anyInt());
    }

    @Test
    @DisplayName("③ 通道未开启：直接返回 0，连 mapper 查询都不做（不做无谓 IO）")
    void 通道未开启时不查询() {
        Fixture f = fixture(false);

        Map<String, Object> stats = f.service().purgeOrphanVectors();

        assertEquals(0, stats.get("purged"));
        verify(f.mapper(), never()).selectOrphanVectorIds(anyInt());
        verify(f.client(), never()).deleteIndex(anyList());
    }

    @Test
    @DisplayName("④ 状态位回写异常也不会死循环：达到批次上限即停止")
    void 批次上限守卫() {
        Fixture f = fixture(true);
        // 永远返回非空 → 只能靠 maxBatches 上限退出，验证守卫有效
        when(f.mapper().selectOrphanVectorIds(BATCH)).thenReturn(List.of(1L));
        when(f.client().deleteIndex(anyList()))
                .thenReturn(new FastApiClient.IndexDeleteResult(1, 1));

        Map<String, Object> stats = f.service().purgeOrphanVectors();

        assertEquals(20, stats.get("batches"), "必须在上限内停止，否则会无限循环");
        verify(f.client(), times(20)).deleteIndex(anyList());
    }

    // ==================================================================
    // ⑤⑥⑦ 自查询一致性巡检：把「向量库静默退化」变成可自动发现的问题
    //
    // ★ 为什么必须有它：退化只影响检索质量、不影响任何计数，
    //   所以 reconcile 会一直显示「索引一致」；此前唯一的发现手段是人工抽查。
    // ==================================================================

    private static Map<String, Object> sample(long id, String content) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("content", content);
        return row;
    }

    private static FastApiClient.VectorHit hit(long chunkId, double score) {
        return new FastApiClient.VectorHit(chunkId, score, score, Map.of());
    }

    @Test
    @DisplayName("⑤ 自查询巡检：每条都能命中自己 → passed 全通过、failed=0")
    void 自查询巡检全部通过() {
        Fixture f = fixture(true);
        when(f.mapper().selectRandomActiveSamples(20)).thenReturn(List.of(
                sample(1L, "C语言中值传递与地址传递的区别是……"),
                sample(2L, "TCP 三次握手用于建立可靠连接……")));
        when(f.client().search("C语言中值传递与地址传递的区别是……", 5, null, null))
                .thenReturn(List.of(hit(9L, 0.31), hit(1L, 0.978)));
        when(f.client().search("TCP 三次握手用于建立可靠连接……", 5, null, null))
                .thenReturn(List.of(hit(2L, 0.981)));

        Map<String, Object> stats = f.service().selfCheckVectorConsistency(20);

        assertEquals(2, stats.get("checked"));
        assertEquals(2, stats.get("passed"));
        assertEquals(0, stats.get("failed"));
        assertEquals(0.978, stats.get("minScore"));
    }

    @Test
    @DisplayName("⑥ 自查询巡检：有块命中不了自己 → 计入 failed，并把 id 报出来")
    void 自查询巡检发现退化() {
        Fixture f = fixture(true);
        when(f.mapper().selectRandomActiveSamples(20)).thenReturn(List.of(
                sample(1L, "健康块正文，长度足够长以避免阈值噪声……"),
                sample(2L, "退化块正文，向量库写歪了导致查不到自己……")));
        when(f.client().search(startsWith("健康块"), eq(5), isNull(), isNull()))
                .thenReturn(List.of(hit(1L, 0.977)));
        when(f.client().search(startsWith("退化块"), eq(5), isNull(), isNull()))
                .thenReturn(List.of(hit(7L, 0.42)));

        Map<String, Object> stats = f.service().selfCheckVectorConsistency(20);

        assertEquals(2, stats.get("checked"));
        assertEquals(1, stats.get("passed"));
        assertEquals(1, stats.get("failed"), "退化必须被计出来，否则静默失效永不可见");
        assertEquals(List.of(2L), stats.get("failedIds"));
    }

    @Test
    @DisplayName("⑦ 通道未开启：返回 skipped，不判失败（不是库坏了，是通道没开）")
    void 自查询巡检在通道关闭时跳过() {
        Fixture f = fixture(false);

        Map<String, Object> stats = f.service().selfCheckVectorConsistency(20);

        assertEquals(0, stats.get("checked"));
        assertEquals(true, stats.get("skipped"));
        verify(f.mapper(), never()).selectRandomActiveSamples(anyInt());
    }
}
