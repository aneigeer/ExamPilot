package com.examprep.service.ai.agent;

import com.examprep.mapper.AsyncTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 异步任务启动恢复
 *
 * <p>只处理「已开始执行」的 RUNNING（{@code started_at IS NOT NULL}）：
 * 从未启动的 PENDING 不动，留给 Python worker 消费。
 *
 * <p>若 Java 与 Python 同时重启，Python 可能通过 XAUTOCLAIM 继续执行该任务；
 * 由于终态守卫存在，其回调会被忽略并照常 XACK —— 这是预期行为（结果不重复交付）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AsyncJobRecovery implements ApplicationRunner {

    private final AsyncTaskMapper mapper;

    @Override
    public void run(ApplicationArguments args) {
        int count = mapper.failStaleRunning("服务重启，任务中断");
        if (count > 0) {
            log.warn("[Job] 启动恢复：{} 个残留 RUNNING 任务已置 FAILED", count);
        }
    }
}
