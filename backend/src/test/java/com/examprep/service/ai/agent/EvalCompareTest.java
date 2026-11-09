package com.examprep.service.ai.agent;

import com.examprep.common.BizException;
import com.examprep.common.ResultCode;
import com.examprep.dto.ai.EvalCompareVO;
import com.examprep.entity.AiEvalRun;
import com.examprep.mapper.AiEvalRunMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 评估对比接口。
 *
 * <p><b>为什么值得测</b>：对比的价值全在「差值算得对不对」，而这里有一堆静默陷阱：
 * <ul>
 *   <li>跨 layer 硬算差值 → 会给出「Recall@5 变成 4.25 步」这种荒谬数字，且不报错；</li>
 *   <li>{@code metrics_json} 里是 {@code "91.7%"} 这种<b>带单位字符串</b>，
 *       按 JSON 类型判断会全部当「非数值」→ 对比功能等于没用；</li>
 *   <li>分母为 0 时算通过率 → 0% 而不是「无数据」，报告会被读成"全线失败"。</li>
 * </ul>
 * 本测试用<b>真实 metrics_json 形态</b>（取自 {@code ai-service/eval/*_eval.py} 的实际键）来锁这些行为。
 */
@DisplayName("评估对比：同层差值 / 跨层拒绝 / 缺参 / 404")
class EvalCompareTest {

    private AiEvalRunMapper evalRunMapper;
    private EvalCompareService service;

    @BeforeEach
    void setUp() {
        evalRunMapper = mock(AiEvalRunMapper.class);
        service = new EvalCompareService(evalRunMapper, new ObjectMapper());
    }

    private AiEvalRun run(Long id, String layer, int total, int passed, String metricsJson) {
        AiEvalRun run = new AiEvalRun();
        run.setId(id);
        run.setLayer(layer);
        run.setStatus(AiEvalRun.STATUS_SUCCESS);
        run.setTotal(total);
        run.setPassed(passed);
        run.setMetricsJson(metricsJson);
        run.setCreatedAt(LocalDateTime.of(2026, 10, 31, 12, 0, 0));
        return run;
    }

    /** 检索侧真实形态：百分比字符串 + 数值字符串 + "-" 占位 */
    private static final String RETRIEVAL_BASE =
            "{\"用例数\":30,\"Recall@5\":\"90.0%\",\"MRR\":\"0.800\",\"平均延迟\":\"1234 ms\",\"误拒率\":\"-\"}";

    private static final String RETRIEVAL_TARGET =
            "{\"用例数\":30,\"Recall@5\":\"91.7%\",\"MRR\":\"0.845\",\"平均延迟\":\"1000 ms\",\"误拒率\":\"-\"}";

    @Test
    @DisplayName("同层：百分比/带单位字符串都算得出差值，非数值原样返回字符串")
    void comparesSameLayerMetrics() {
        when(evalRunMapper.selectById(1L)).thenReturn(
                run(1L, AiEvalRun.LAYER_RETRIEVAL, 30, 27, RETRIEVAL_BASE));
        when(evalRunMapper.selectById(2L)).thenReturn(
                run(2L, AiEvalRun.LAYER_RETRIEVAL, 30, 28, RETRIEVAL_TARGET));

        EvalCompareVO vo = service.compare(1L, 2L);

        assertTrue(vo.getComparable());
        assertNull(vo.getMessage());
        // 两侧快照
        assertEquals(0.9, vo.getBase().getPassRate(), 1e-9, "27/30");
        assertEquals(0.9333, vo.getTarget().getPassRate(), 1e-9, "28/30，4 位小数 HALF_UP");
        assertEquals(30, vo.getBase().getTotal());

        var delta = vo.getDelta();
        assertEquals(0.0333, (Double) delta.get("passRate"), 1e-9, "通过率 +3.33 个百分点");
        // "91.7%" - "90.0%" → 0.917 - 0.90
        assertEquals(0.017, (Double) delta.get("metrics.Recall@5"), 1e-9, "百分点差必须算得出来");
        assertEquals(0.045, (Double) delta.get("metrics.MRR"), 1e-9);
        assertEquals(-234.0, (Double) delta.get("metrics.平均延迟"), 1e-9, "1234 ms → 1000 ms");
        assertEquals(0.0, (Double) delta.get("metrics.用例数"), 1e-9);
        // 非数值：原样返回目标侧字符串，不猜、不当 0
        assertEquals("-", delta.get("metrics.误拒率"));
        // 键必须带 metrics. 前缀，避免与顶层字段撞名
        assertFalse(delta.containsKey("Recall@5"));
    }

    @Test
    @DisplayName("跨层：comparable=false + 提示文案 + delta=null（不给误导性数字）")
    void refusesCrossLayerComparison() {
        when(evalRunMapper.selectById(1L)).thenReturn(
                run(1L, AiEvalRun.LAYER_RETRIEVAL, 30, 27, RETRIEVAL_BASE));
        when(evalRunMapper.selectById(2L)).thenReturn(
                run(2L, AiEvalRun.LAYER_AGENT, 12, 10,
                        "{\"任务数\":12,\"通过率\":\"83.3%\",\"工具成功率\":\"95.0%\"}"));

        EvalCompareVO vo = service.compare(1L, 2L);

        assertFalse(vo.getComparable());
        assertNull(vo.getDelta(), "跨层指标集不相交，差值必须为空");
        assertNotNull(vo.getMessage());
        assertTrue(vo.getMessage().contains("RETRIEVAL") && vo.getMessage().contains("AGENT"),
                vo.getMessage());
        // 两侧快照仍然返回，便于人自己看
        assertEquals(AiEvalRun.LAYER_AGENT, vo.getTarget().getLayer());
        assertEquals(0.8333, vo.getTarget().getPassRate(), 1e-9);
    }

    @Test
    @DisplayName("id 缺失：400（直接调用也要拦住，不依赖 Web 层绑定）")
    void rejectsMissingIds() {
        BizException e1 = assertThrows(BizException.class, () -> service.compare(null, 2L));
        assertEquals(ResultCode.PARAM_ERROR.getCode(), e1.getCode());
        BizException e2 = assertThrows(BizException.class, () -> service.compare(1L, null));
        assertEquals(ResultCode.PARAM_ERROR.getCode(), e2.getCode());
    }

    @Test
    @DisplayName("记录不存在：404（分别校验 base 与 target）")
    void reportsNotFound() {
        when(evalRunMapper.selectById(1L)).thenReturn(null);
        when(evalRunMapper.selectById(2L)).thenReturn(run(2L, AiEvalRun.LAYER_RETRIEVAL, 30, 28, RETRIEVAL_TARGET));

        BizException base = assertThrows(BizException.class, () -> service.compare(1L, 2L));
        assertEquals(ResultCode.NOT_FOUND.getCode(), base.getCode());
        assertTrue(base.getMessage().contains("1"), base.getMessage());

        when(evalRunMapper.selectById(7L)).thenReturn(run(7L, AiEvalRun.LAYER_RETRIEVAL, 30, 27, RETRIEVAL_BASE));
        BizException target = assertThrows(BizException.class, () -> service.compare(7L, 99L));
        assertEquals(ResultCode.NOT_FOUND.getCode(), target.getCode());
        assertTrue(target.getMessage().contains("99"), target.getMessage());
    }

    @Test
    @DisplayName("metrics_json 缺失/坏 JSON/非对象：按空指标处理，不 500")
    void toleratesBrokenMetricsJson() {
        when(evalRunMapper.selectById(1L)).thenReturn(run(1L, AiEvalRun.LAYER_RETRIEVAL, 30, 27, null));
        when(evalRunMapper.selectById(2L)).thenReturn(run(2L, AiEvalRun.LAYER_RETRIEVAL, 30, 28, "{not json"));
        when(evalRunMapper.selectById(3L)).thenReturn(run(3L, AiEvalRun.LAYER_RETRIEVAL, 30, 28, "[1,2,3]"));

        assertTrue(service.compare(1L, 2L).getBase().getMetrics().isEmpty());
        assertTrue(service.compare(1L, 2L).getTarget().getMetrics().isEmpty());
        assertTrue(service.compare(1L, 3L).getTarget().getMetrics().isEmpty());
        // 坏 JSON 也要能算出 passRate 差值
        assertEquals(0.0333, (Double) service.compare(1L, 2L).getDelta().get("passRate"), 1e-9);
    }

    @Test
    @DisplayName("total=0 / null：passRate 为 null（不是 0%），差值也为 null")
    void zeroTotalYieldsNullRate() {
        AiEvalRun empty = run(1L, AiEvalRun.LAYER_RETRIEVAL, 0, 0, "{}");
        when(evalRunMapper.selectById(1L)).thenReturn(empty);
        when(evalRunMapper.selectById(2L)).thenReturn(run(2L, AiEvalRun.LAYER_RETRIEVAL, 30, 28, RETRIEVAL_TARGET));

        EvalCompareVO vo = service.compare(1L, 2L);

        assertNull(vo.getBase().getPassRate(), "无用例 → null，避免显示 0% 通过率");
        assertFalse(vo.getDelta().containsKey("passRate"),
                "一侧缺失 → 差值键根本不出现（缺失即无法比较），不写 null");
    }

    @Test
    @DisplayName("numeric 解析规则：百分比转 0..1、剥单位、不可解析返回 null")
    void numericParsingRules() {
        assertEquals(0.917, EvalCompareService.numeric("91.7%"), 1e-9);
        assertEquals(0.845, EvalCompareService.numeric("0.845"), 1e-9);
        assertEquals(1234.0, EvalCompareService.numeric("1234 ms"), 1e-9);
        assertEquals(4.25, EvalCompareService.numeric("4.25 步"), 1e-9);
        assertEquals(30.0, EvalCompareService.numeric(30), 1e-9);
        assertNull(EvalCompareService.numeric("-"));
        assertNull(EvalCompareService.numeric("无"));
        assertNull(EvalCompareService.numeric("no_final×2"));
        assertNull(EvalCompareService.numeric(""));
        assertNull(EvalCompareService.numeric(null));
    }
}
