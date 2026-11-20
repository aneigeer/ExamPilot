package com.examprep.service.ai.agent;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.examprep.entity.AiEvalRun;
import com.examprep.mapper.AiEvalRunMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评测发布门禁汇总
 *
 * <p>对 5 个评测层各取「最新一次 SUCCESS 运行」，按硬阈值给出红绿灯：
 * <ul>
 *   <li>RETRIEVAL：Recall@5 ≥ 0.80、MRR ≥ 0.70</li>
 *   <li>AGENT：通过率 ≥ 0.80</li>
 *   <li>REFUSAL：域外拒答率 ≥ 0.90、域内误拒率 ≤ 0.10（硬门槛，写死）</li>
 *   <li>GRADING：等级一致率 ≥ 0.70、得分率 MAE ≤ 0.25</li>
 *   <li>STUDY_PLAN：JSON 合规率 ≥ 0.95、错题 ID 零幻觉率 = 1.00、降级率 ≤ 0.50</li>
 * </ul>
 *
 * <p>缺失该层运行记录时 {@code passed=false} 且 {@code missing=true}——
 * 「没跑过」不能当成「通过」。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvalGateService {

    private final AiEvalRunMapper evalRunMapper;
    private final ObjectMapper objectMapper;

    public Map<String, Object> gateSummary() {
        List<Map<String, Object>> layers = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        List<String> missing = new ArrayList<>();

        for (String layer : AiEvalRun.LAYERS) {
            AiEvalRun run = evalRunMapper.selectOne(Wrappers.<AiEvalRun>lambdaQuery()
                    .eq(AiEvalRun::getLayer, layer)
                    .eq(AiEvalRun::getStatus, AiEvalRun.STATUS_SUCCESS)
                    .orderByDesc(AiEvalRun::getId)
                    .last("LIMIT 1"));

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("layer", layer);
            if (run == null) {
                item.put("missing", true);
                item.put("passed", false);
                item.put("runId", null);
                item.put("checks", List.of());
                layers.add(item);
                missing.add(layer);
                failed.add(layer);
                continue;
            }

            JsonNode metrics = readMetrics(run.getMetricsJson());
            List<Map<String, Object>> checks = checksOf(layer, metrics);
            boolean passed = checks.stream().allMatch(c -> Boolean.TRUE.equals(c.get("ok")));

            item.put("missing", false);
            item.put("runId", run.getId());
            item.put("createdAt", run.getCreatedAt());
            item.put("finishedAt", run.getFinishedAt());
            item.put("total", run.getTotal());
            item.put("passedCases", run.getPassed());
            item.put("checks", checks);
            item.put("passed", passed);
            layers.add(item);
            if (!passed) {
                failed.add(layer);
            }
        }

        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("generatedAt", LocalDateTime.now());
        vo.put("allPassed", failed.isEmpty());
        vo.put("failedLayers", failed);
        vo.put("missingLayers", missing);
        vo.put("layers", layers);
        return vo;
    }

    // ==================================================================
    // 各层阈值
    // ==================================================================

    private List<Map<String, Object>> checksOf(String layer, JsonNode metrics) {
        List<Map<String, Object>> checks = new ArrayList<>();
        switch (layer) {
            case AiEvalRun.LAYER_RETRIEVAL -> {
                checks.add(check("Recall@5", metric(metrics, "Recall@5", "recallAt5"), 0.80, true));
                checks.add(check("MRR", metric(metrics, "MRR", "mrr"), 0.70, true));
            }
            case AiEvalRun.LAYER_AGENT -> checks.add(
                    check("通过率", metric(metrics, "通过率", "passRate"), 0.80, true));
            case AiEvalRun.LAYER_REFUSAL -> {
                checks.add(check("域外拒答率", metric(metrics, "refusalRate", "域外拒答率"), 0.90, true));
                checks.add(check("域内误拒率", metric(metrics, "falseRejectRate", "域内误拒率"), 0.10, false));
            }
            case AiEvalRun.LAYER_GRADING -> {
                checks.add(check("等级一致率", metric(metrics, "levelAgreement", "等级一致率"), 0.70, true));
                checks.add(check("得分率MAE", metric(metrics, "scoreMae", "得分率MAE"), 0.25, false));
            }
            case AiEvalRun.LAYER_STUDY_PLAN -> {
                checks.add(check("JSON合规率", metric(metrics, "jsonCompliance", "JSON合规率"), 0.95, true));
                checks.add(check("错题ID零幻觉率", metric(metrics, "idZeroHallucinationRate", "错题ID零幻觉率"), 1.00, true));
                checks.add(check("降级率", metric(metrics, "degradeRate", "降级率"), 0.50, false));
            }
            default -> {
            }
        }
        return checks;
    }

    private static Map<String, Object> check(String name, Double actual, double threshold, boolean atLeast) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("actual", actual);
        m.put("threshold", (atLeast ? ">= " : "<= ") + format(threshold));
        m.put("ok", actual != null && (atLeast ? actual >= threshold : actual <= threshold));
        return m;
    }

    /**
     * 从 metrics_json 取指标值。
     *
     * <p>兼容三类写法：数字（0.95）、百分比字符串（"95.0%" → 0.95）、纯数字字符串（"0.845"）。
     * 取不到返回 null —— 门禁按「不通过」处理，绝不默认放行。
     */
    private static Double metric(JsonNode metrics, String... keys) {
        if (metrics == null) {
            return null;
        }
        for (String key : keys) {
            JsonNode node = metrics.get(key);
            if (node == null || node.isNull()) {
                continue;
            }
            if (node.isNumber()) {
                return node.asDouble();
            }
            String text = node.asText("").trim();
            if (text.isEmpty() || "-".equals(text)) {
                continue;
            }
            boolean percent = text.endsWith("%");
            if (percent) {
                text = text.substring(0, text.length() - 1).trim();
            }
            try {
                double value = Double.parseDouble(text);
                return percent ? value / 100.0 : value;
            } catch (NumberFormatException ignored) {
                // 非数值（如 "无"）→ 继续尝试下一个键
            }
        }
        return null;
    }

    private JsonNode readMetrics(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.warn("[EvalGate] metrics_json 解析失败：{}", e.getMessage());
            return null;
        }
    }

    private static String format(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
