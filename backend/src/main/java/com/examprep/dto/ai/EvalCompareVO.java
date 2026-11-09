package com.examprep.dto.ai;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 两次离线评估的对比结果（{@code GET /admin/ai/eval/compare}）。
 *
 * <p>用途：回答「这次调整让指标变好还是变差了」。因此结构上刻意把
 * <b>两侧原始快照</b>（{@link Side}）与<b>差值</b>（{@code delta}）分开——
 * 管理端既能画对比表，也能直接展示「+1.7 个百分点」。
 */
@Data
@Schema(description = "评估对比结果")
public class EvalCompareVO {

    @Schema(description = "基线评估（baseId）")
    private Side base;

    @Schema(description = "目标评估（targetId）")
    private Side target;

    /**
     * 是否可比较（同 layer 才为 true）
     *
     * <p>跨层（RETRIEVAL vs AGENT）指标集不相交，差值没有意义，
     * 所以此时 {@code delta} 为 null，只给提示文案。
     */
    @Schema(description = "是否可比较（同 layer 才为 true）")
    private Boolean comparable;

    @Schema(description = "不可比较时的提示文案")
    private String message;

    /**
     * 差值表
     *
     * <p>键规则：{@code passRate} 为裸键；指标项统一加前缀 {@code metrics.}
     * （如 {@code metrics.Recall@5}），避免与顶层字段撞名字。
     * 值规则：两侧都能解析成数字 → 差（target - base，4 位小数）；
     * 否则 → 原样返回目标侧的字符串（如 {@code "无"} / {@code "timeout×3"}）。
     * <b>键缺失 = 无法比较</b>（某一侧缺失或非数值），不会返回 {@code key: null}。
     */
    @Schema(description = "差值表：passRate + metrics.<键>；缺失的键表示无法比较")
    private Map<String, Object> delta;

    /** 单侧评估快照 */
    @Data
    @Schema(description = "单侧评估快照")
    public static class Side {

        @Schema(description = "评估记录 ID")
        private Long id;

        @Schema(description = "RETRIEVAL / AGENT")
        private String layer;

        @Schema(description = "RUNNING / SUCCESS / FAILED")
        private String status;

        @Schema(description = "运行时间")
        private LocalDateTime createdAt;

        @Schema(description = "用例总数")
        private Integer total;

        @Schema(description = "通过数")
        private Integer passed;

        /**
         * 通过率 = passed / total，保留 4 位小数 HALF_UP
         *
         * <p>{@code total} 为 null/0 时返回 null（不是 0，避免「0%通过」的误导）。
         */
        @Schema(description = "通过率 0..1；无用例为 null")
        private Double passRate;

        @Schema(description = "metrics_json 解析结果（原样，不做单位换算）")
        private Map<String, Object> metrics;
    }
}
