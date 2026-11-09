package com.examprep.service.ai.agent;

import com.examprep.common.BizException;
import com.examprep.common.ResultCode;
import com.examprep.dto.ai.EvalCompareVO;
import com.examprep.entity.AiEvalRun;
import com.examprep.mapper.AiEvalRunMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 两次离线评估的对比（只读）。
 *
 * <p><b>为什么值得单独做</b>：评估跑完只会得到一堆「通过率 80%」这样的孤立数字，
 * 真正要回答的是「这次改动到底变好还是变差、好多少」。没有对比，
 * 每次调参都只能凭印象，退化也无法及时发现。
 *
 * <p><b>★ metrics_json 的真实形态决定实现</b>（别按理想 schema 想当然）：
 * 生产者（{@code ai-service/eval/retrieval_eval.py} / {@code agent_eval.py}）写入的是
 * <b>已格式化的中文键字符串</b>，例如：
 * <pre>
 *   {"用例数": 30, "Recall@5": "91.7%", "MRR": "0.845", "平均延迟": "1234 ms", "误拒率": "-"}
 *   {"任务数": 12, "通过率": "80.0%", "平均步数": "4.25", "P95 延迟": "6120 ms",
 *    "工具成功率": "95.0%", "主要失败原因": "no_final×2"}
 * </pre>
 * 所以「数值型算差」不能只看 JSON 类型：{@code "91.7%"} / {@code "1234 ms"} / {@code "4.25"}
 * 都是可比较的量。本服务用 {@link #numeric(Object)} 做<b>保守解析</b>：
 * <ul>
 *   <li>百分号 → 转成 0..1 小数（与 {@code passRate} 同量纲，差值即「百分点/100」）；</li>
 *   <li>只剥离单位后缀（ms/毫秒/步…），数字部分能解析才算数值；</li>
 *   <li>解析不了一律返回 {@code null} → 该键退回「原样返回目标侧字符串」，
 *       绝不猜（{@code "-"} / {@code "无"} / 失败原因串保持原样）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvalCompareService {

    /** 数字后面的常见单位（剥离后才尝试 parseDouble） */
    private static final Pattern UNIT_SUFFIX =
            Pattern.compile("(?i)\\s*(ms|毫秒|秒|s|步|个|条|次|分|items?)$");

    private final AiEvalRunMapper evalRunMapper;

    private final ObjectMapper objectMapper;

    /**
     * 对比两次评估。
     *
     * @param baseId   基线评估 ID
     * @param targetId 目标评估 ID
     * @return 对比结果（跨层时 {@code comparable=false} + 提示文案，{@code delta=null}）
     * @throws BizException id 缺失（400）或记录不存在（404）
     */
    public EvalCompareVO compare(Long baseId, Long targetId) {
        if (baseId == null || targetId == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "baseId 与 targetId 必填");
        }
        AiEvalRun base = require(baseId);
        AiEvalRun target = require(targetId);

        EvalCompareVO vo = new EvalCompareVO();
        vo.setBase(sideOf(base));
        vo.setTarget(sideOf(target));

        boolean sameLayer = base.getLayer() != null && base.getLayer().equals(target.getLayer());
        vo.setComparable(sameLayer);
        if (!sameLayer) {
            // 跨层指标集不相交（检索侧是 Recall@K / MRR，Agent 侧是步数 / 工具成功率），
            // 强行算差值只会产出误导性数字，因此宁可返回空。
            vo.setMessage("两次评估的 layer 不同（" + base.getLayer() + " vs " + target.getLayer()
                    + "），指标集不相交，无法逐项对比；请选择同 layer 的两次评估。");
            vo.setDelta(null);
            return vo;
        }
        vo.setDelta(delta(vo.getBase(), vo.getTarget()));
        return vo;
    }

    private AiEvalRun require(Long id) {
        AiEvalRun run = evalRunMapper.selectById(id);
        if (run == null) {
            throw new BizException(ResultCode.NOT_FOUND, "评估记录不存在：" + id);
        }
        return run;
    }

    private EvalCompareVO.Side sideOf(AiEvalRun run) {
        EvalCompareVO.Side side = new EvalCompareVO.Side();
        side.setId(run.getId());
        side.setLayer(run.getLayer());
        side.setStatus(run.getStatus());
        side.setCreatedAt(run.getCreatedAt());
        side.setTotal(run.getTotal());
        side.setPassed(run.getPassed());
        side.setPassRate(rate(run.getPassed(), run.getTotal()));
        side.setMetrics(parseMetrics(run.getMetricsJson()));
        return side;
    }

    /**
     * 差值表：passRate 裸键 + metrics.<原键>，顺序稳定（LinkedHashMap）。
     *
     * <p><b>无法比较的键直接不出现，而不是给 null</b>：全局 Jackson 配置是
     * {@code default-property-inclusion: non_null}（application.yml），Map 里的 null 值
     * 是否被序列化掉取决于 Jackson 版本与内容包含配置——与其依赖这种不确定行为，
     * 不如在构造阶段就省略，语义也更好：<b>键缺失 = 无法比较</b>。
     */
    private Map<String, Object> delta(EvalCompareVO.Side base, EvalCompareVO.Side target) {
        Map<String, Object> delta = new LinkedHashMap<>();
        putIfComparable(delta, "passRate", diff(base.getPassRate(), target.getPassRate()));

        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(base.getMetrics().keySet());
        keys.addAll(target.getMetrics().keySet());
        for (String key : keys) {
            Object baseValue = base.getMetrics().get(key);
            Object targetValue = target.getMetrics().get(key);
            Double baseNum = numeric(baseValue);
            Double targetNum = numeric(targetValue);
            String deltaKey = "metrics." + key;
            if (baseNum != null && targetNum != null) {
                delta.put(deltaKey, round4(targetNum - baseNum));
            } else if (targetValue != null) {
                // 非数值（"-" / "无" / 失败原因串）：原样返回目标侧文本，不做任何加工
                delta.put(deltaKey, String.valueOf(targetValue));
            }
            // 其余情况（一侧缺失 / 目标侧缺失）：不写键 —— 缺失即「无法比较」
        }
        return delta;
    }

    private static void putIfComparable(Map<String, Object> delta, String key, Double value) {
        if (value != null) {
            delta.put(key, value);
        }
    }

    /**
     * 解析 metrics_json（宽容失败：坏 JSON 只记日志，不影响对比主流程）。
     *
     * @param json metrics_json 原文，可为 null
     * @return 键值表；非对象 JSON / 解析失败返回空表
     */
    private Map<String, Object> parseMetrics(String json) {
        Map<String, Object> empty = new LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            return empty;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node == null || !node.isObject()) {
                log.warn("[EvalCompare] metrics_json 不是 JSON 对象，按空指标处理");
                return empty;
            }
            return objectMapper.convertValue(node, new com.fasterxml.jackson.core.type.TypeReference<
                    LinkedHashMap<String, Object>>() {
            });
        } catch (Exception e) {
            log.warn("[EvalCompare] metrics_json 解析失败，按空指标处理：{}", e.getMessage());
            return empty;
        }
    }

    /**
     * 保守地把指标值解析成数字（解析不了返回 null）。
     *
     * <p>支持：{@code 30}、{@code "0.845"}、{@code "91.7%"}（→ 0.917）、{@code "1234 ms"}（→ 1234）。
     *
     * @param value JSON 原值
     * @return 数值或 {@code null}
     */
    static Double numeric(Object value) {
        if (value == null || value instanceof Boolean) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        String text = value.toString().trim();
        if (text.isEmpty()) {
            return null;
        }
        boolean percent = text.endsWith("%") || text.endsWith("％");
        if (percent) {
            text = text.substring(0, text.length() - 1).trim();
        }
        text = UNIT_SUFFIX.matcher(text).replaceAll("").trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            double parsed = Double.parseDouble(text);
            if (Double.isNaN(parsed) || Double.isInfinite(parsed)) {
                return null;
            }
            return percent ? parsed / 100.0 : parsed;
        } catch (NumberFormatException e) {
            // "-" / "无" / "timeout×2" 等：不是数值，交给调用方原样返回
            return null;
        }
    }

    /** 通过率：4 位小数 HALF_UP；total 为 null/0 → null（不是 0） */
    static Double rate(Integer passed, Integer total) {
        if (total == null || total <= 0 || passed == null) {
            return null;
        }
        return BigDecimal.valueOf(passed)
                .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private static Double diff(Double base, Double target) {
        if (base == null || target == null) {
            return null;
        }
        return round4(target - base);
    }

    private static Double round4(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }
}
