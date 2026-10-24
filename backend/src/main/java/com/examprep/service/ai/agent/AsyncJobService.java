package com.examprep.service.ai.agent;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.examprep.common.BizException;
import com.examprep.common.PageResult;
import com.examprep.common.ResultCode;
import com.examprep.dto.ai.AsyncTaskVO;
import com.examprep.entity.AiEvalRun;
import com.examprep.entity.AsyncTask;
import com.examprep.mapper.AiEvalRunMapper;
import com.examprep.mapper.AsyncTaskMapper;
import com.examprep.service.ai.AiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 异步任务生产者（Java → Redis Stream → Python）
 *
 * <p>设计要点：
 * <ul>
 *   <li>提交时同步执行一次 XADD（单命令，无需线程池）；</li>
 *   <li>同类型活动任务的唯一性以数据库生成列 + UNIQUE 为准，查询只做快速失败；</li>
 *   <li>Redis 不可用时任务置 FAILED 并返回 503，不抛未处理异常；</li>
 *   <li>进度/完成/失败回调带终态守卫，避免 Java 重启置 FAILED 后被晚到回调改写。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncJobService {

    private final StringRedisTemplate redis;
    private final AsyncTaskMapper mapper;
    private final AiEvalRunMapper evalRunMapper;
    private final AiProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * 提交任务
     *
     * @param type      任务类型（EVAL_ 前缀走评估流，其余走业务流）
     * @param payload   任务参数
     * @param createdBy 提交人
     */
    public AsyncTask submit(String type, Map<String, Object> payload, Long createdBy) {
        AiProperties.Jobs cfg = properties.getJobs();
        if (!cfg.isEnabled()) {
            throw new BizException(ResultCode.AI_SERVICE_UNAVAILABLE,
                    "任务队列未启用（app.ai.jobs.enabled=false）");
        }
        if (!StringUtils.hasText(type)) {
            throw new BizException(ResultCode.PARAM_ERROR, "任务类型不能为空");
        }
        // 快速失败 + 友好提示；唯一性最终由 DB UNIQUE 兜底
        if (mapper.selectActiveByType(type) != null) {
            throw new BizException(ResultCode.TOO_MANY_REQUESTS, "同类型任务正在执行，请稍后再试");
        }

        String payloadJson = writeJson(payload == null ? Map.of() : payload);
        AsyncTask task = new AsyncTask();
        task.setType(type);
        task.setStream(streamOf(type));
        task.setStatus(AsyncTask.STATUS_PENDING);
        task.setProgress(0);
        task.setPayload(payloadJson);
        task.setCreatedBy(createdBy);
        task.setCreatedAt(LocalDateTime.now());
        try {
            mapper.insert(task);
        } catch (DuplicateKeyException e) {
            // 并发提交撞上 active_type 唯一约束
            throw new BizException(ResultCode.TOO_MANY_REQUESTS, "同类型任务正在执行，请稍后再试");
        }

        if (isEvalType(type)) {
            AiEvalRun evalRun = new AiEvalRun();
            evalRun.setTaskId(task.getId());
            evalRun.setLayer(layerOf(type));
            evalRun.setStatus(AiEvalRun.STATUS_RUNNING);
            evalRun.setTotal(0);
            evalRun.setPassed(0);
            evalRun.setCreatedAt(LocalDateTime.now());
            evalRunMapper.insert(evalRun);
        }

        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        try {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("jobId", String.valueOf(task.getId()));
            fields.put("type", type);
            fields.put("payload", payloadJson);
            fields.put("traceId", traceId);
            MapRecord<String, String, String> record =
                    StreamRecords.string(fields).withStreamKey(task.getStream());
            redis.opsForStream().add(record);
            log.info("[Job] 已投递 {} → {}（id={}, trace={}）", type, task.getStream(), task.getId(), traceId);
        } catch (Exception e) {
            String reason = "Redis 投递失败：" + e.getMessage();
            mapper.markFailed(task.getId(), truncate(reason, 900));
            markEvalRunFailed(task.getId(), reason);
            log.warn("[Job] 投递失败 id={} type={}：{}", task.getId(), type, e.getMessage());
            throw new BizException(ResultCode.AI_SERVICE_UNAVAILABLE,
                    "任务队列不可用，请检查 Redis 是否已启动");
        }
        return task;
    }

    /** 进度回调：只更新 PENDING/RUNNING 的任务 */
    public boolean progress(Long id, Integer percent) {
        int safe = Math.max(0, Math.min(100, percent == null ? 0 : percent));
        return mapper.markProgress(id, safe) > 0;
    }

    /** 完成回调；返回 false 表示终态守卫命中（任务已被置 FAILED/CANCELLED） */
    public boolean complete(Long id, String resultJson) {
        AsyncTask task = mapper.selectById(id);
        boolean updated = mapper.markSuccess(id, resultJson) > 0;
        if (!updated) {
            log.info("[Job] 忽略完成回调 id={}（任务已是终态）", id);
            return false;
        }
        if (task != null && isEvalType(task.getType())) {
            markEvalRunSuccess(id, resultJson);
        }
        return true;
    }

    /** 失败回调；返回 false 表示终态守卫命中 */
    public boolean fail(Long id, String error) {
        AsyncTask task = mapper.selectById(id);
        boolean updated = mapper.markFailed(id, truncate(error, 900)) > 0;
        if (!updated) {
            log.info("[Job] 忽略失败回调 id={}（任务已是终态）", id);
            return false;
        }
        if (task != null && isEvalType(task.getType())) {
            markEvalRunFailed(id, error);
        }
        return true;
    }

    public AsyncTask get(Long id) {
        AsyncTask task = mapper.selectById(id);
        if (task == null) {
            throw new BizException(ResultCode.NOT_FOUND, "任务不存在");
        }
        return task;
    }

    public PageResult<AsyncTaskVO> list(long page, long size) {
        long safePage = Math.max(1, page);
        long safeSize = Math.max(1, Math.min(100, size));
        IPage<AsyncTask> result = mapper.selectPage(
                new Page<>(safePage, safeSize),
                Wrappers.<AsyncTask>lambdaQuery().orderByDesc(AsyncTask::getId));
        return PageResult.of(result, AsyncTaskVO::of);
    }

    /**
     * 按类型路由到独立 stream：
     * <ul>
     *   <li>{@code EVAL_*} → {@code exam:eval}（评估）</li>
     *   <li>{@code WIKI_*} → {@code exam:wiki}（LLM 蒸馏，分钟级长任务）</li>
     *   <li>其余业务任务 → {@code exam:jobs}</li>
     * </ul>
     */
    private String streamOf(String type) {
        AiProperties.Jobs cfg = properties.getJobs();
        if (isEvalType(type)) {
            return cfg.getEvalStream();
        }
        if (type != null && type.startsWith("WIKI_")) {
            return cfg.getWikiStream();
        }
        return cfg.getStream();
    }

    private static boolean isEvalType(String type) {
        return type != null && type.startsWith("EVAL_");
    }

    private static String layerOf(String type) {
        return type.contains("RETRIEVAL")
                ? AiEvalRun.LAYER_RETRIEVAL : AiEvalRun.LAYER_AGENT;
    }

    /** 评估结果落库：metrics/detail 直接取 Python 返回的结构 */
    private void markEvalRunSuccess(Long taskId, String resultJson) {
        try {
            JsonNode node = objectMapper.readTree(resultJson == null ? "{}" : resultJson);
            AiEvalRun update = new AiEvalRun();
            update.setStatus(AiEvalRun.STATUS_SUCCESS);
            update.setTotal(node.path("total").asInt(0));
            update.setPassed(node.path("passed").asInt(0));
            update.setMetricsJson(node.path("metrics").isMissingNode()
                    ? null : node.path("metrics").toString());
            update.setDetailJson(node.path("detail").isMissingNode()
                    ? null : node.path("detail").toString());
            update.setReportPath(node.path("report_path").asText(null));
            update.setFinishedAt(LocalDateTime.now());
            evalRunMapper.update(update,
                    Wrappers.<AiEvalRun>lambdaUpdate().eq(AiEvalRun::getTaskId, taskId));
        } catch (Exception e) {
            log.warn("[Job] 评估结果落库失败 task={}：{}", taskId, e.getMessage());
        }
    }

    private void markEvalRunFailed(Long taskId, String error) {
        try {
            AiEvalRun update = new AiEvalRun();
            update.setStatus(AiEvalRun.STATUS_FAILED);
            update.setError(truncate(error, 900));
            update.setFinishedAt(LocalDateTime.now());
            evalRunMapper.update(update,
                    Wrappers.<AiEvalRun>lambdaUpdate().eq(AiEvalRun::getTaskId, taskId));
        } catch (Exception e) {
            log.warn("[Job] 评估失败状态落库失败 task={}：{}", taskId, e.getMessage());
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BizException(ResultCode.PARAM_ERROR, "任务参数序列化失败");
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
