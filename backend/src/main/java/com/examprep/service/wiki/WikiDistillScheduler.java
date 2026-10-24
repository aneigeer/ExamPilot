package com.examprep.service.wiki;

import com.examprep.entity.AsyncTask;
import com.examprep.service.ai.AiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Wiki 每日异步蒸馏调度
 *
 * <p><b>为什么是异步</b>：本地 7B 模型蒸馏 10 个主题约 5–10 分钟，
 * 绝不能挂在请求线程上。这里只做一次 XADD（毫秒级），
 * 真正的生成由 Python worker 消费 {@code exam:wiki} 完成。
 *
 * <p>任务队列未启用时静默跳过（只打一行 info），不影响应用启动与其余功能。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.wiki.distill", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class WikiDistillScheduler {

    private final WikiService wikiService;
    private final WikiProperties wikiProperties;
    private final AiProperties aiProperties;

    @Scheduled(cron = "${app.wiki.distill.cron:0 30 3 * * ?}")
    public void dailyDistill() {
        if (!wikiProperties.isEnabled() || !wikiProperties.getDistill().isEnabled()) {
            return;
        }
        if (!aiProperties.getJobs().isEnabled()) {
            log.info("[Wiki] 定时蒸馏跳过：异步任务队列未启用（app.ai.jobs.enabled=false）");
            return;
        }
        try {
            AsyncTask task = wikiService.enqueueDistill("ALL", null, null);
            log.info("[Wiki] 已入队每日蒸馏任务 #{}（stream={}）", task.getId(), task.getStream());
        } catch (Exception e) {
            // 同类型任务已在跑 / Redis 不可用：都属于「今天不蒸馏」而不是故障
            log.warn("[Wiki] 定时蒸馏入队失败（今天跳过）：{}", e.getMessage());
        }
    }
}
