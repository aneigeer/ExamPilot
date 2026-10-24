package com.examprep.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Agent 相关线程池
 *
 * <p>只保留 SSE 转发线程池（任务提交走请求线程同步 XADD，见 AsyncJobService）。
 * 队列满时直接拒绝，由 Controller 转 429；不使用 CallerRunsPolicy，避免阻塞 Tomcat 线程。
 */
@Configuration
public class AgentConfig {

    @Bean("agentStreamExecutor")
    public ThreadPoolTaskExecutor agentStreamExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("agent-stream-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }

    /**
     * SSE 心跳调度器（Java → 前端注释行，防代理层空闲断连）
     *
     * <p>Python → Java 方向不设心跳，靠 TCP 保活 + 120s 总超时处理。
     */
    @Bean(name = "agentHeartbeatScheduler", destroyMethod = "shutdown")
    public ScheduledExecutorService agentHeartbeatScheduler() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "agent-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }
}
