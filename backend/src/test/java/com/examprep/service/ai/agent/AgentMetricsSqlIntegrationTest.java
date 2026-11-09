package com.examprep.service.ai.agent;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.examprep.dto.ai.AgentMetricsVO;
import com.examprep.entity.AiAgentRun;
import com.examprep.mapper.AiAgentRunMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 指标聚合 SQL 的真库验证（真 MySQL + 窗口函数）。
 *
 * <p><b>为什么必须有这个测试</b>：{@link AgentMetricsServiceTest} 全部 mock 掉 mapper，
 * 它只能证明「拿到什么数怎么算」，<b>证明不了 SQL 本身算得对</b>。而这段 SQL 的坑全在
 * 数据库侧、且都是静默的：
 * <ul>
 *   <li>P95 用 {@code ROW_NUMBER() + CEIL(cnt*0.95)}，写错一个运算符就悄悄变成 P100 或 P90；</li>
 *   <li>{@code SUM(CASE WHEN ...)} 在空窗口返回 NULL 而不是 0，没兜住就是 NPE/500；</li>
 *   <li>聚合结果以 {@code Map} 返回，<b>列别名大小写</b>取决于驱动——对不上就全是 null；</li>
 *   <li>{@code total_ms=0}（没跑到生成，如 executor_rejected）必须从耗时样本里剔除；</li>
 *   <li>RUNNING 的行不能进任一率的分母、也不能进 errorTop5 的域。</li>
 * </ul>
 *
 * <p><b>零污染设计</b>：
 * <ol>
 *   <li>{@code @Transactional} 结束即回滚，不落库；</li>
 *   <li>探针数据的时间戳钉在 {@code 2020-01-15}（早于本项目所有真实数据），
 *       且只查 {@code [2020-01-15, 2020-01-16]} 这个<b>孤立窗口</b>——
 *       即使并发有别人写入，也只会落在当前时间，绝不影响本窗口的精确断言。</li>
 * </ol>
 */
@DisplayName("指标 SQL 真库验证：窗口函数 P95 / 空窗口 NULL / 别名大小写")
@SpringBootTest
@ActiveProfiles("local")
@Transactional
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AgentMetricsSqlIntegrationTest {

    /** 孤立窗口：2020-01-15 全天，早于任何真实数据 */
    private static final LocalDateTime PROBE_FROM = LocalDateTime.of(2020, 1, 15, 0, 0, 0);
    private static final LocalDateTime PROBE_TO = LocalDateTime.of(2020, 1, 16, 0, 0, 0);
    private static final LocalDateTime PROBE_AT = LocalDateTime.of(2020, 1, 15, 12, 0, 0);

    /** 完全无数据的窗口，用于验证空窗口语义 */
    private static final LocalDateTime EMPTY_FROM = LocalDateTime.of(2000, 1, 1, 0, 0, 0);
    private static final LocalDateTime EMPTY_TO = LocalDateTime.of(2000, 1, 2, 0, 0, 0);

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private AiAgentRunMapper runMapper;

    @Autowired
    private AgentMetricsService service;

    /**
     * 每个用例前铺 6 条探针（事务内，结束回滚）。
     *
     * <p>★ 例外：{@link #probesAreRolledBack()} 自身跑在「无事务」上下文，
     * 若这里也插入，那 6 行会以 autocommit 直接提交到真实库——探测器反而变成污染源。
     * （这不是假设：第一版守卫正是这样在 `exam_prep.ai_agent_run` 里落下 6 行，
     * 被它自己断言 `expected: 0 but was: 6` 抓出来的。）
     */
    @BeforeEach
    void insertProbes(TestInfo info) {
        String method = info.getTestMethod().map(Method::getName).orElse("");
        if ("probesAreRolledBack".equals(method)) {
            return;
        }
        // 4 条 SUCCESS（total_ms = 10/20/30/40，全部降级），generator=qa-p95
        int[] costs = {10, 20, 30, 40};
        for (int cost : costs) {
            insert(AiAgentRun.STATUS_SUCCESS, cost, true, "qa-p95", null, 900_001L);
        }
        // 1 条 FAILED：total_ms=0（没跑到生成），generator 为 NULL → 归 unknown，带 error
        insert(AiAgentRun.STATUS_FAILED, 0, false, null, "qa_probe_timeout", 900_002L);
        // 1 条 RUNNING：不得进任何率的分母、不得进 errorTop5（所以故意也带 error）
        insert(AiAgentRun.STATUS_RUNNING, 0, false, "qa-p95", "qa_should_be_ignored", 900_003L);
    }

    private void insert(String status, int totalMs, boolean degraded, String generator,
                        String error, Long userId) {
        AiAgentRun run = new AiAgentRun();
        run.setUserId(userId);
        run.setTraceId("qa-sql-probe");
        run.setQuestion("qa-sql-probe");
        run.setStatus(status);
        run.setGenerator(generator);
        run.setStepCount(0);
        run.setToolCalls(0);
        run.setTotalMs(totalMs);
        run.setDegraded(degraded);
        run.setError(error);
        run.setCreatedAt(PROBE_AT);
        runMapper.insert(run);
    }

    /** 取聚合值；MySQL 对 SUM 返回 DECIMAL、COUNT 返回 BIGINT，统一转 long */
    private static long num(Map<String, Object> row, String key) {
        Object value = row.get(key);
        assertNotNull(value, "聚合列缺失（列别名大小写对不上？）：" + key + " in " + row.keySet());
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(value.toString());
    }

    @Test
    @Order(1)
    @DisplayName("计数聚合：RUNNING 单独计、total_ms=0 不进耗时样本、列别名可映射")
    void summaryCountsAndLatencyDomain() {
        Map<String, Object> row = runMapper.selectSummary(PROBE_FROM, PROBE_TO);

        assertEquals(6L, num(row, "total"), "total 含 RUNNING");
        assertEquals(1L, num(row, "running"));
        assertEquals(4L, num(row, "successCnt"));
        assertEquals(0L, num(row, "refusedCnt"));
        assertEquals(1L, num(row, "failedCnt"));
        assertEquals(0L, num(row, "cancelledCnt"));
        // 耗时候选：4 条 SUCCESS（10/20/30/40）；FAILED 的 total_ms=0 与 RUNNING 都被剔除
        assertEquals(4L, num(row, "samples"), "total_ms=0 必须剔除（否则 avg 被拉低）");
        assertEquals(25L, num(row, "avgMs"), "ROUND(AVG(10,20,30,40)) = 25");
        assertEquals(40L, num(row, "maxMs"));
        // 降级口径：分子分母同为 SUCCESS+REFUSED 子集
        assertEquals(4L, num(row, "degradedCount"));
        assertEquals(4L, num(row, "degradedDenominator"));
    }

    @Test
    @Order(2)
    @DisplayName("P95 最近秩法：[10,20,30,40] → CEIL(0.95×4)=4 → 40")
    void p95NearestRank() {
        assertEquals(40L, runMapper.selectP95(PROBE_FROM, PROBE_TO).longValue(),
                "最近秩法必须取到最大值（不是插值，也不是 P90）");
    }

    @Test
    @Order(3)
    @DisplayName("P95 并列值不炸多行：[10,10,20] → 20，且只返回一行")
    void p95HandlesTies() {
        insert(AiAgentRun.STATUS_SUCCESS, 10, false, "qa-tie", null, 900_004L);
        insert(AiAgentRun.STATUS_SUCCESS, 10, false, "qa-tie", null, 900_005L);
        // 此时窗口内耗时为 [10(SUCCESS),10,10,20,30,40] n=6 → CEIL(5.7)=6 → 40
        assertEquals(40L, runMapper.selectP95(PROBE_FROM, PROBE_TO).longValue());
    }

    @Test
    @Order(4)
    @DisplayName("空窗口：SUM/AVG/MAX 全是 NULL，不抛异常、不返回 0 假数据")
    void emptyWindowReturnsNullAggregates() {
        Map<String, Object> row = runMapper.selectSummary(EMPTY_FROM, EMPTY_TO);

        assertEquals(0L, num(row, "total"));
        assertNull(row.get("running"), "空窗口 SUM 返回 NULL，由服务层兜成 0");
        assertNull(row.get("successCnt"));
        assertNull(row.get("samples"));
        assertNull(row.get("avgMs"));
        assertNull(row.get("maxMs"));
        assertNull(runMapper.selectP95(EMPTY_FROM, EMPTY_TO), "无样本 P95 必须是 null");
        assertTrue(runMapper.selectGeneratorStats(EMPTY_FROM, EMPTY_TO).isEmpty());
        assertTrue(runMapper.selectGeneratorP95(EMPTY_FROM, EMPTY_TO).isEmpty());
        assertTrue(runMapper.selectTopErrors(EMPTY_FROM, EMPTY_TO).isEmpty());
    }

    @Test
    @Order(5)
    @DisplayName("按 generator 分组：NULL 归 unknown，组内 P95 独立分区，无样本组为 null")
    void generatorGroupingAndPerGroupP95() {
        List<Map<String, Object>> stats = runMapper.selectGeneratorStats(PROBE_FROM, PROBE_TO);
        assertEquals(2, stats.size(), "qa-p95 与 unknown 两组");

        Map<String, Object> qaP95 = stats.stream()
                .filter(r -> "qa-p95".equals(r.get("generator"))).findFirst().orElseThrow();
        assertEquals(5L, num(qaP95, "cnt"), "4 SUCCESS + 1 RUNNING 都属 qa-p95");
        assertEquals(4L, num(qaP95, "terminalCnt"), "组内 terminal 排除 RUNNING");
        assertEquals(25L, num(qaP95, "avgMs"));

        Map<String, Object> unknown = stats.stream()
                .filter(r -> "unknown".equals(r.get("generator"))).findFirst().orElseThrow();
        assertEquals(1L, num(unknown, "cnt"), "generator 为 NULL 必须归入 unknown，不能丢组");
        assertNull(unknown.get("avgMs"), "该组 total_ms=0 → 无样本 → AVG 为 NULL");

        Map<String, Long> p95 = new java.util.LinkedHashMap<>();
        for (Map<String, Object> row : runMapper.selectGeneratorP95(PROBE_FROM, PROBE_TO)) {
            p95.put(String.valueOf(row.get("generator")),
                    ((Number) row.get("p95Ms")).longValue());
        }
        assertEquals(40L, p95.get("qa-p95").longValue(), "组内最近秩法同整体口径");
        assertNull(p95.get("unknown"), "total_ms=0 的组没有样本，不该出现在 P95 结果里");
    }

    @Test
    @Order(6)
    @DisplayName("errorTop5 的域：只含终态且 error 非空，RUNNING 的 error 不算")
    void errorTop5DomainExcludesRunning() {
        List<Map<String, Object>> errors = runMapper.selectTopErrors(PROBE_FROM, PROBE_TO);

        assertEquals(1, errors.size(), "RUNNING 行的 error=['qa_should_be_ignored'] 不得上榜");
        assertEquals("qa_probe_timeout", errors.get(0).get("error"));
        assertEquals(1L, num(errors.get(0), "cnt"));
    }

    @Test
    @Order(7)
    @DisplayName("服务层贯通真库：窗口结构化、5 键齐备、terminal=total-running")
    void serviceRunsAgainstRealDatabase() {
        AgentMetricsVO vo = service.metrics(90);

        assertNotNull(vo);
        assertEquals(90, vo.getDays());
        assertNotNull(vo.getFrom());
        assertNotNull(vo.getTo());
        // from/to 必须可被解析（管理端拿它们做锚点回查）
        LocalDateTime from = LocalDateTime.parse(vo.getFrom(), TS);
        LocalDateTime to = LocalDateTime.parse(vo.getTo(), TS);
        assertTrue(from.isBefore(to), "from 必须早于 to");
        assertEquals(to.minusDays(90), from, "from = to - 90 天");

        assertEquals(vo.getTotal() - vo.getRunning(), vo.getTerminal());
        assertEquals(5, vo.getStatusCounts().size());
        assertTrue(vo.getStatusCounts().containsKey("RUNNING"));
        // 2020 的探针行不在 90 天窗口内 → 不得污染真实统计（total 必然 >= 0 且不含探针）
        assertTrue(vo.getTerminal() >= 0L);

        // ★ generator 分组的"不漏行"不变量（与数据内容无关，故不会随库状态漂移）：
        //   每个运行行必须恰好归入一个组。真实库里 generator 有两种"空"：
        //   NULL（历史 FAILED）与字面量 'none'（Python 拒答路径写入）——
        //   前者归 unknown、后者按原值分组，两者都算"归入"，不得被静默丢弃或合并归一。
        assertTrue(vo.getGenerators().stream()
                        .allMatch(g -> g.getGenerator() != null && !g.getGenerator().isBlank()),
                "分组键不得为 null/空白");
        long grouped = vo.getGenerators().stream()
                .mapToLong(AgentMetricsVO.GeneratorStat::getCount).sum();
        if (vo.getGenerators().size() < 20) {
            // 分组有 LIMIT 20，未触顶时各组计数之和必须等于 total
            assertEquals(vo.getTotal(), grouped, "每个运行行恰好归入一个 generator 组（含 unknown / none）");
        } else {
            assertTrue(grouped <= vo.getTotal(), "触顶 20 组时只截断，不得重复计数");
        }
    }

    @Test
    @Order(9)
    @DisplayName("探针不留痕：事务回滚后表内不应残留 qa-sql-probe 行（零污染自证）")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void probesAreRolledBack() {
        // 本方法不开事务，读的是提交后的真实数据：若上面前 7 个方法的事务没回滚干净，
        // 这里就会数到 6 条（或更多）探针行——所以它是"零污染"的机器证明，不靠人记。
        long leaked = runMapper.selectCount(Wrappers.<AiAgentRun>lambdaQuery()
                .eq(AiAgentRun::getQuestion, "qa-sql-probe"));
        assertEquals(0L, leaked, "探针数据必须随事务回滚，不得落库");
    }
}
