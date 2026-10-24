package com.examprep.service.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

/**
 * T09 双通道降级测试
 *
 * <p><b>测试的核心命题</b>：<b>向量通道挂掉时，问答接口必须仍然可用</b>。
 * 这不是「顺便测一下」，而是整个扩展的<b>安全底线</b>——
 * FastAPI / Qdrant / 模型 / Docker 引擎任一不可用，系统都必须退化为
 * 「ngram 字面检索 + 抽取式生成」，接口仍返回 200。
 *
 * <p>用 {@link MockRestServiceServer} 模拟 FastAPI 的各种故障响应，
 * <b>不需要真的起 Python 服务</b>，因此在 Docker 未运行的机器上也能跑。
 */
@DisplayName("T09：向量通道降级（FastAPI 不可用时系统仍可用）")
class FastApiClientTest {

    private static final String BASE = "http://127.0.0.1:8000";

    // ==================================================================
    // 测试脚手架：用 MockRestServiceServer 构造一个可控的 FastAPI
    // ==================================================================

    /** 被测客户端 + 其 MockRestServiceServer */
    private record Fixture(FastApiClient client, MockRestServiceServer server,
                           AiProperties properties) {
    }

    /**
     * 构造一个「RestClient 指向 MockRestServiceServer」的 FastApiClient
     *
     * <p>做法：先建带自定义 requestFactory 的 RestClient，
     * 再用 reflection 把 FastApiClient 的 final 字段替换掉。
     * 这样无需真实网络即可精确模拟超时 / 503 / 500。
     */
    private Fixture fixture(AiProperties props) {
        AiProperties.FastApi cfg = props.getFastapi();
        cfg.setEnabled(true);
        cfg.setBaseUrl(BASE);
        cfg.setPathPrefix("/v1");
        // 健康检查缓存 60s：search() 不会自己调 health()，缓存保证一个测试内只打一次
        cfg.setHealthCacheMillis(60_000L);

        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        RestClient.Builder builder = RestClient.builder();
        // 关键：先建一个 builder，绑定 MockRestServiceServer 之后再 set requestFactory
        RestClient temp = builder.build();
        holder[0] = MockRestServiceServer.bindTo(builder).build();
        RestClient mockRestClient = builder.build();

        FastApiClient client = new FastApiClient(props, new com.fasterxml.jackson.databind.ObjectMapper());
        // 把三个 RestClient 与熔断器换成指向 mock 的实例
        inject(client, "searchClient", mockRestClient);
        inject(client, "healthClient", mockRestClient);
        inject(client, "indexClient", mockRestClient);
        if (temp == null) {
            throw new IllegalStateException("unreachable");
        }
        return new Fixture(client, holder[0], props);
    }

    /** 反射替换 final 字段（测试专用） */
    private static void inject(Object target, String fieldName, Object value) {
        try {
            java.lang.reflect.Field f = target.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("注入 " + fieldName + " 失败", e);
        }
    }

    private static final String HEALTHY_BODY = """
            {"status":"ok","version":"1.0.0",
             "embedding":{"ready":true,"dim":512,"model_id":"bge-small-zh-v1.5","detail":""},
             "qdrant":{"ready":true,"detail":""},
             "ocr":{"ready":false,"detail":"not installed"},
             "llm":{"ready":true,"detail":""}}
            """;

    private static final String SEARCH_BODY = """
            {"hits":[{"chunk_id":5,"score":0.87,"payload":
              {"title":"简述C语言中「值传递」与「地址传递」的区别","source_type":"QUESTION",
               "ref_type":"QUESTION","ref_id":5,"subject_id":5,"knowledge_id":1,
               "difficulty":3,"hot_count":4,"rerank_score":0.91}}],
             "took_ms":42,"dim":512}
            """;

    // ==================================================================
    // ① FastAPI 不可用 → 检索返回空、available=false、绝不抛异常
    // ==================================================================

    @Test
    @DisplayName("① FastAPI 连接失败：search 返回空 list 且不抛异常（降级前提）")
    void fastApi不可用时检索返回空() {
        AiProperties props = new AiProperties();
        props.getVector().setEnabled(true);
        Fixture f = fixture(props);

        // 模拟「FastAPI 进程没起 / Qdrant 挂」：搜索直接 500。
        // FastApiClient 必须把它吞成空 list（降级），而不是向上抛异常。
        f.server().expect(once(), requestTo("/v1/search"))
                .andRespond(withServerError());

        List<FastApiClient.VectorHit> hits = f.client().search("值传递和地址传递", 20, null, null);

        assertNotNull(hits, "必须返回非 null（上层按空 list 判断降级）");
        assertTrue(hits.isEmpty(), "FastAPI 不可用时应返回空 list，实际 " + hits.size());
        f.server().verify();
    }

    @Test
    @DisplayName("①-b 孤儿向量删除：成功解析 deleted，失败不抛异常也不计熔断")
    void 删除孤儿向量() {
        AiProperties props = new AiProperties();
        props.getVector().setEnabled(true);
        Fixture f = fixture(props);

        f.server().expect(once(), requestTo("/v1/index/delete"))
                .andExpect(method(POST))
                .andRespond(withSuccess("{\"deleted\":2,\"requested\":2}",
                        MediaType.APPLICATION_JSON));
        // MockRestServiceServer 不允许请求发生后追加期望，故第二个期望必须提前声明
        f.server().expect(once(), requestTo("/v1/index/delete"))
                .andRespond(withServerError());

        assertEquals(2, f.client().deleteChunks(List.of(11L, 12L)),
                "应返回 Python 侧报告的 deleted 条数");

        // 失败路径：删除失败必须吞掉异常（下架主流程不能被向量清理拖垮）
        assertEquals(0, f.client().deleteChunks(List.of(13L)),
                "删除失败应返回 0，而不是抛异常");
        assertEquals(0, f.client().breaker().consecutiveFailures(),
                "删除失败【不应】计入熔断——它不该影响检索通道的可用性判定");
        f.server().verify();
    }

    @Test
    @DisplayName("①-c 空列表不发起请求（避免无谓往返）")
    void 空列表不删向量() {
        AiProperties props = new AiProperties();
        props.getVector().setEnabled(true);
        Fixture f = fixture(props);
        // 不设任何期望：一旦发请求就会失败
        assertEquals(0, f.client().deleteChunks(List.of()));
        assertEquals(0, f.client().deleteChunks(null));
        f.server().verify();
    }

    @Test
    @DisplayName("② 健康检查 503 → available()=false，熔断计数递增；失败结果【不缓存】")
    void 健康检查失败则不可用() {
        AiProperties props = new AiProperties();
        props.getVector().setEnabled(true);
        Fixture f = fixture(props);

        f.server().expect(once(), requestTo("/v1/health"))
                .andRespond(withStatus(SERVICE_UNAVAILABLE));
        // ★ 失败快照【不缓存】，所以 available() 会重新探测一次。
        // 这是刻意的设计：若缓存失败结果，FastAPI 恢复后仍要等缓存过期才能重新发现，
        // 叠加熔断窗口后恢复耗时被放大到分钟级（实测踩过）。
        // 探测频率由熔断器兜住——连续失败 3 次即熔断，不会打爆对方。
        f.server().expect(once(), requestTo("/v1/health"))
                .andRespond(withStatus(SERVICE_UNAVAILABLE));

        FastApiClient.HealthSnapshot health = f.client().health();
        assertFalse(health.reachable(), "503 不应视为可达");
        assertFalse(health.ready(), "健康检查失败时不得声称就绪");
        assertFalse(f.client().available(), "健康检查失败时 available() 必须为 false");
        assertTrue(f.client().breaker().consecutiveFailures() >= 1, "应计入熔断计数");
    }

    @Test
    @DisplayName("③ 连续 3 次失败 → 熔断，第 4 次不再发请求（耗时 < 100ms）")
    void 连续失败触发熔断() {
        AiProperties props = new AiProperties();
        props.getVector().setEnabled(true);
        Fixture f = fixture(props);
        // ★ 本用例要连续触发 3 次真实请求，所以必须关掉健康缓存，
        //   否则第 2、3 次 health() 会直接命中缓存、不发请求，熔断计数永远只有 1。
        f.properties().getFastapi().setHealthCacheMillis(0L);

        // 先一次性注册 3 个 health 期望（MockRestServiceServer 不允许在发出实际请求后再追加期望）
        for (int i = 0; i < 3; i++) {
            f.server().expect(once(), requestTo("/v1/health"))
                    .andRespond(withStatus(SERVICE_UNAVAILABLE));
        }
        for (int i = 0; i < 3; i++) {
            f.client().health();
        }
        assertTrue(f.client().breaker().isOpen(), "连续 3 次失败后应熔断");
        assertEquals(3, f.client().breaker().consecutiveFailures(), "失败计数应为 3");

        // 熔断期间：available() 直接返回 false，<b>不再发起任何 HTTP 请求</b>
        // （这里不再调 server().verify()，因为它会认为 3 个期望已全部满足）
        long start = System.currentTimeMillis();
        boolean available = f.client().available();
        long cost = System.currentTimeMillis() - start;

        assertFalse(available, "熔断期间 available() 必须为 false");
        assertTrue(cost < 100, "熔断判定应 < 100ms（无网络等待），实际 " + cost + "ms");
        System.out.println("熔断生效，available() 耗时 " + cost + "ms（无网络请求）");
    }

    @Test
    @DisplayName("④ FastAPI 返回 500：search 返回空 list，不抛异常")
    void 服务端500时降级() {
        AiProperties props = new AiProperties();
        props.getVector().setEnabled(true);
        Fixture f = fixture(props);

        f.server().expect(once(), requestTo("/v1/search"))
                .andRespond(withServerError());

        List<FastApiClient.VectorHit> hits = f.client().search("测试", 20, null, null);
        assertTrue(hits.isEmpty(), "500 时应返回空 list，实际 " + hits.size());
    }

    @Test
    @DisplayName("⑤ 检索无命中返回 200 + 空 hits —— 这是正常结果不是错误，不应熔断")
    void 无命中不熔断() {
        AiProperties props = new AiProperties();
        props.getVector().setEnabled(true);
        Fixture f = fixture(props);

        f.server().expect(once(), requestTo("/v1/search"))
                .andRespond(withSuccess("{\"hits\":[],\"took_ms\":8,\"dim\":512}",
                        MediaType.APPLICATION_JSON));

        List<FastApiClient.VectorHit> hits = f.client().search("量子纠缠", 20, null, null);

        assertTrue(hits.isEmpty(), "无命中应为空 list");
        assertFalse(f.client().breaker().isOpen(),
                "「无命中」是正常业务结果，绝不能计入熔断（否则会误伤正常查询）");
    }

    @Test
    @DisplayName("⑥ 正常响应：解析 hits + payload，chunk_id 与 score 正确")
    void 正常检索解析() {
        AiProperties props = new AiProperties();
        props.getVector().setEnabled(true);
        Fixture f = fixture(props);

        f.server().expect(once(), requestTo("/v1/search"))
                .andRespond(withSuccess(SEARCH_BODY, MediaType.APPLICATION_JSON));

        List<FastApiClient.VectorHit> hits = f.client().search("值传递和地址传递", 20, null, null);

        assertEquals(1, hits.size(), "应解析出 1 条命中");
        FastApiClient.VectorHit hit = hits.get(0);
        assertEquals(5L, hit.chunkId());
        assertEquals(0.87, hit.score(), 1e-6);
        assertEquals(0.91, hit.rerankScore(), 1e-6);
        assertEquals("QUESTION", hit.refType());
        assertEquals(5L, hit.refId());
        assertEquals(1L, hit.knowledgeId());
        assertEquals(4L, hit.hotCount());
        assertTrue(hit.title().contains("值传递"), "payload title 应解析出来：" + hit.title());
    }

    @Test
    @DisplayName("⑦ 开关关闭：一次网络请求都不发（省掉无谓的连接超时）")
    void 开关关闭不发请求() {
        AiProperties props = new AiProperties();
        props.getVector().setEnabled(false);
        props.getFastapi().setEnabled(false);
        FastApiClient client = new FastApiClient(props,
                new com.fasterxml.jackson.databind.ObjectMapper());

        assertFalse(client.available(), "开关关闭时 available() 必须为 false");
        assertTrue(client.search("任意问题", 20, null, null).isEmpty(),
                "开关关闭时应直接返回空，不发请求");
        assertFalse(client.health().ready());
        System.out.println("开关关闭验证通过：未发起任何网络请求");
    }

    @Test
    @DisplayName("⑧ 响应体损坏（非法 JSON）：降级为空 list，不抛异常")
    void 响应损坏时降级() {
        AiProperties props = new AiProperties();
        props.getVector().setEnabled(true);
        Fixture f = fixture(props);

        f.server().expect(once(), requestTo("/v1/search"))
                .andRespond(withSuccess("这不是 JSON{{{", MediaType.APPLICATION_JSON));

        assertTrue(f.client().search("测试", 20, null, null).isEmpty(),
                "非法 JSON 应降级为空 list");
    }

    // ==================================================================
    // ⑨ 双通道融合算法
    // ==================================================================

    @Test
    @DisplayName("⑨ 双通道融合：去重 + 双通道加成 + 统一五因子重排")
    void 双通道融合() {
        AiProperties props = new AiProperties();
        VectorChunkMerger merger = new VectorChunkMerger(props);

        ChunkContext vectorOnly = ctx(1L, "向量独有：动态分配内存", 0.92, 1L);
        ChunkContext dualA = ctx(2L, "值传递与地址传递", 0.30, 2L);   // 两路都有，向量分低
        ChunkContext dualB = ctx(2L, "值传递与地址传递", 0.95, 2L);
        ChunkContext ngramOnly = ctx(3L, "ngram独有：冒泡排序", 0.10, 3L);

        List<ChunkContext> vector = List.of(vectorOnly, dualA);
        List<ChunkContext> ngram = List.of(dualB, ngramOnly);

        Set<Long> dual = merger.dualHitIds(vector, ngram);
        assertEquals(Set.of(2L), dual, "只有 chunk 2 应被判定为双通道命中");

        List<ChunkContext> merged = merger.merge(vector, ngram, "值传递", Set.of());

        System.out.println();
        System.out.println("── 双通道融合结果 ──");
        merged.forEach(c -> System.out.printf("  chunk#%d finalScore=%.4f  %s%n",
                c.chunkId(), c.finalScore(), c.title()));

        assertEquals(3, merged.size(), "去重后应为 3 块（4 条去重成 3）");
        assertEquals(2L, merged.get(0).chunkId(),
                "双通道命中的块应排第一（加成 + 两路都相关）");
        for (int i = 0; i < merged.size() - 1; i++) {
            assertTrue(merged.get(i).finalScore() >= merged.get(i + 1).finalScore(),
                    "融合结果必须按 finalScore 降序");
        }
    }

    @Test
    @DisplayName("⑩ 融合：向量不可用（空列表）时退化为纯 ngram，结果与单通道一致")
    void 纯向量不可用时退化() {
        AiProperties props = new AiProperties();
        VectorChunkMerger merger = new VectorChunkMerger(props);
        List<ChunkContext> ngram = List.of(ctx(5L, "值传递与地址传递", 0.88, 5L));

        List<ChunkContext> merged = merger.merge(List.of(), ngram, "值传递", Set.of());

        assertEquals(1, merged.size(), "向量空时应直接用 ngram 结果");
        assertEquals(5L, merged.get(0).chunkId());
        assertTrue(merged.get(0).finalScore() > 0, "仍应有有效分数");
    }

    @Test
    @DisplayName("⑪ 融合：两路皆空 → 返回空 list（上层据此拒答）")
    void 两路皆空() {
        VectorChunkMerger merger = new VectorChunkMerger(new AiProperties());
        assertTrue(merger.merge(List.of(), List.of(), "任意", Set.of()).isEmpty());
        assertTrue(merger.merge(null, null, "任意", Set.of()).isEmpty(),
                "null 入参也应安全返回空 list");
    }

    @Test
    @DisplayName("⑫ 薄弱知识点加权生效（向量命中薄弱知识点应加分）")
    void 薄弱知识点加权() {
        AiProperties props = new AiProperties();
        VectorChunkMerger merger = new VectorChunkMerger(props);

        ChunkContext withKp = ctx(7L, "指针与内存管理", 0.5, 7L);
        ChunkContext withoutKp = ctx(8L, "冒泡排序", 0.5, null);

        List<ChunkContext> noBoost = merger.merge(
                List.of(withKp, withoutKp), List.of(), "内存", Set.of());
        List<ChunkContext> boosted = merger.merge(
                List.of(withKp, withoutKp), List.of(), "内存", Set.of(7L));

        double delta = boosted.get(0).finalScore() - noBoost.get(0).finalScore();
        System.out.println();
        System.out.println("── 薄弱知识点加权 ──");
        System.out.printf("  薄弱知识点 %s 加权前后分差 = %.4f（应 > 0）%n",
                withKp.knowledgeId(), delta);
        assertTrue(delta > 0, "命中薄弱知识点应获得额外加权");
    }

    private ChunkContext ctx(Long id, String title, double ftScore, Long knowledgeId) {
        return new ChunkContext(id, "QUESTION", id, title,
                title + " 的正文内容", 5L, knowledgeId, 3, "QUESTION", id, null,
                java.time.LocalDateTime.now().minusDays(1), 0L, ftScore, ftScore);
    }

    // ==================================================================
    // ⑬⑭ 孤儿向量清理：块置 INVALID 后必须把 Qdrant 里的向量点也删掉
    //
    // ★ 为什么必须删而不是改写 payload：Qdrant 检索按 payload 快照过滤，
    //   块 INVALID 后 payload 里的 status 仍是 ACTIVE，过滤拦不住它，
    //   孤儿会占掉 top-K 名额。实测 80 个命中里 32 个是已失效块。
    // ==================================================================

    @Test
    @DisplayName("⑬ 向量删除成功：解析 deleted/requested，不抛异常")
    void 向量删除成功() {
        AiProperties props = new AiProperties();
        props.getVector().setEnabled(true);
        Fixture f = fixture(props);

        f.server().expect(once(), requestTo("/v1/index/delete"))
                .andRespond(withSuccess("{\"deleted\":2,\"requested\":2}",
                        MediaType.APPLICATION_JSON));

        FastApiClient.IndexDeleteResult result = f.client().deleteIndex(List.of(1L, 2L));

        assertNotNull(result, "成功时应返回结果对象");
        assertEquals(2, result.deleted());
        assertEquals(2, result.requested());
        f.server().verify();
    }

    @Test
    @DisplayName("⑭ 向量删除失败：返回 null（保留状态位，下次 reindex 重试清理）")
    void 向量删除失败返回null() {
        AiProperties props = new AiProperties();
        props.getVector().setEnabled(true);
        Fixture f = fixture(props);

        f.server().expect(once(), requestTo("/v1/index/delete"))
                .andRespond(withServerError());

        assertNull(f.client().deleteIndex(List.of(1L)), "删除失败必须返回 null 而不是抛异常");
        f.server().verify();
    }
}
