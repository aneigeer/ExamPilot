package com.examprep.service.ai;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 自研熔断器（零依赖，约 40 行核心逻辑）
 *
 * <p><b>为什么自研而不引 resilience4j</b>：为了 AI 能力给系统增加一个运维组件不划算，
 * 使用场景多一个依赖就多一个故障点。熔断逻辑本身只有三个状态变量。
 *
 * <p><b>状态机</b>：
 * <pre>
 *   关闭态：正常调用
 *     └─ 失败（超时 / 5xx / 网络异常）→ consecutiveFailures++
 *          └─ 达到阈值（默认 3）→ 开启态，openUntil = now + 60s
 *   开启态：canUse() 返回 false，调用方直接走抽取式（用户无感）
 *     └─ 60s 后自动回到关闭态（半开：允许一次试探）
 *          ├─ 成功 → consecutiveFailures = 0
 *          └─ 失败 → 重新开启
 * </pre>
 */
@Slf4j
public class CircuitBreaker {

    private final String name;
    private final int failureThreshold;
    private final long openMillis;

    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private volatile long openUntil;

    public CircuitBreaker(String name, int failureThreshold, long openMillis) {
        this.name = name;
        this.failureThreshold = Math.max(1, failureThreshold);
        this.openMillis = Math.max(1000L, openMillis);
    }

    /**
     * 当前是否允许调用
     *
     * <p>熔断窗口已过则自动放行（并重置失败计数，实现「半开试探」）。
     */
    public boolean canUse() {
        long now = System.currentTimeMillis();
        if (now > openUntil) {
            if (consecutiveFailures.get() > 0 && openUntil > 0) {
                log.info("[AI] 熔断器 {} 窗口结束，恢复调用", name);
                consecutiveFailures.set(0);
                openUntil = 0L;
            }
            return true;
        }
        return false;
    }

    /** 调用成功：清零失败计数并关闭熔断 */
    public void onSuccess() {
        consecutiveFailures.set(0);
        openUntil = 0L;
    }

    /**
     * 调用失败：计数 +1，达到阈值则开启熔断
     *
     * @return 是否因此次失败触发了熔断
     */
    public boolean onFailure(String reason) {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= failureThreshold) {
            openUntil = System.currentTimeMillis() + openMillis;
            log.warn("[AI] 熔断器 {} 连续失败 {} 次，开启熔断 {} ms（原因：{}），期间自动降级为抽取式",
                    name, failures, openMillis, reason);
            return true;
        }
        log.warn("[AI] 熔断器 {} 第 {}/{} 次失败：{}", name, failures, failureThreshold, reason);
        return false;
    }

    /** 当前连续失败次数（/ai/capability 展示用） */
    public int consecutiveFailures() {
        return consecutiveFailures.get();
    }

    /** 剩余熔断毫秒数；0 表示未熔断 */
    public long remainingOpenMillis() {
        long remain = openUntil - System.currentTimeMillis();
        return Math.max(0L, remain);
    }

    /** 是否处于熔断态 */
    public boolean isOpen() {
        return System.currentTimeMillis() <= openUntil;
    }
}
