package com.examprep.service.ai.agent;

import com.examprep.common.BizException;
import com.examprep.common.ResultCode;
import com.examprep.entity.AiEvalRun;
import com.examprep.entity.AsyncTask;
import com.examprep.mapper.AiEvalRunMapper;
import com.examprep.mapper.AsyncTaskMapper;
import com.examprep.service.ai.AiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 异步任务生产者：开关、去重、双 stream 路由、Redis 失败降级、终态守卫。
 */
@DisplayName("异步任务队列：Java 生产者")
class AsyncJobServiceTest {

    private StringRedisTemplate redis;
    private StreamOperations<String, Object, Object> streamOps;
    private AsyncTaskMapper taskMapper;
    private AiEvalRunMapper evalRunMapper;
    private AiProperties properties;
    private AsyncJobService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        streamOps = mock(StreamOperations.class);
        when(redis.opsForStream()).thenReturn(streamOps);
        taskMapper = mock(AsyncTaskMapper.class);
        evalRunMapper = mock(AiEvalRunMapper.class);
        properties = new AiProperties();
        properties.getJobs().setEnabled(true);
        service = new AsyncJobService(redis, taskMapper, evalRunMapper,
                properties, new ObjectMapper());
    }

    private void stubInsert(long id) {
        doAnswer(invocation -> {
            AsyncTask task = invocation.getArgument(0);
            task.setId(id);
            return 1;
        }).when(taskMapper).insert(any(AsyncTask.class));
    }

    @Test
    @DisplayName("队列关闭时返回 503，不写库、不投递")
    void disabledReturns503() {
        properties.getJobs().setEnabled(false);
        BizException e = assertThrows(BizException.class,
                () -> service.submit("EVAL_AGENT", Map.of(), 1L));
        assertEquals(ResultCode.AI_SERVICE_UNAVAILABLE.getCode(), e.getCode());
        verify(taskMapper, never()).insert(any(AsyncTask.class));
    }

    @Test
    @DisplayName("同类型活动任务快速失败（429）")
    void activeTypeRejected() {
        when(taskMapper.selectActiveByType("EVAL_AGENT")).thenReturn(new AsyncTask());
        BizException e = assertThrows(BizException.class,
                () -> service.submit("EVAL_AGENT", Map.of(), 1L));
        assertEquals(ResultCode.TOO_MANY_REQUESTS.getCode(), e.getCode());
        verify(taskMapper, never()).insert(any(AsyncTask.class));
    }

    @Test
    @DisplayName("并发提交撞唯一约束同样转 429")
    void duplicateInsertRejected() {
        when(taskMapper.selectActiveByType(anyString())).thenReturn(null);
        when(taskMapper.insert(any(AsyncTask.class)))
                .thenThrow(new DuplicateKeyException("uk_async_active_type"));
        BizException e = assertThrows(BizException.class,
                () -> service.submit("EVAL_AGENT", Map.of(), 1L));
        assertEquals(ResultCode.TOO_MANY_REQUESTS.getCode(), e.getCode());
    }

    @Test
    @DisplayName("EVAL_ 前缀走评估流，其余走业务流")
    void routesToSeparateStreams() {
        when(taskMapper.selectActiveByType(anyString())).thenReturn(null);
        stubInsert(11L);

        service.submit("EVAL_RETRIEVAL", Map.of(), 1L);
        service.submit("SOME_JOB", Map.of(), 1L);

        @SuppressWarnings({"unchecked", "rawtypes"})
        org.mockito.ArgumentCaptor<MapRecord<String, Object, Object>> captor =
                (org.mockito.ArgumentCaptor) org.mockito.ArgumentCaptor.forClass(MapRecord.class);
        verify(streamOps, org.mockito.Mockito.times(2)).add(captor.capture());
        assertEquals("exam:eval", captor.getAllValues().get(0).getStream());
        assertEquals("exam:jobs", captor.getAllValues().get(1).getStream());
    }

    @Test
    @DisplayName("WIKI_ 前缀走独立蒸馏流 exam:wiki")
    void routesWikiToDedicatedStream() {
        when(taskMapper.selectActiveByType(anyString())).thenReturn(null);
        stubInsert(14L);
        service.submit("WIKI_DISTILL", Map.of("scope", "ALL"), 1L);

        @SuppressWarnings({"unchecked", "rawtypes"})
        org.mockito.ArgumentCaptor<MapRecord<String, Object, Object>> captor =
                (org.mockito.ArgumentCaptor) org.mockito.ArgumentCaptor.forClass(MapRecord.class);
        verify(streamOps).add(captor.capture());
        assertEquals("exam:wiki", captor.getValue().getStream());
    }

    @Test
    @DisplayName("EVAL_ 任务提交时同步创建评估记录")
    void createsEvalRunForEvalTypes() {
        when(taskMapper.selectActiveByType(anyString())).thenReturn(null);
        stubInsert(12L);
        service.submit("EVAL_AGENT", Map.of(), 1L);
        verify(evalRunMapper).insert(any(AiEvalRun.class));
    }

    @Test
    @DisplayName("Redis 投递失败：任务置 FAILED 并返回 503")
    void redisFailureDegrades() {
        when(taskMapper.selectActiveByType(anyString())).thenReturn(null);
        stubInsert(13L);
        when(redis.opsForStream()).thenThrow(new RuntimeException("redis down"));

        BizException e = assertThrows(BizException.class,
                () -> service.submit("EVAL_AGENT", Map.of(), 1L));
        assertEquals(ResultCode.AI_SERVICE_UNAVAILABLE.getCode(), e.getCode());
        verify(taskMapper).markFailed(eq(13L), contains("Redis"));
    }

    @Test
    @DisplayName("终态守卫：已是终态时 complete 返回 false，不再更新评估记录")
    void terminalGuardOnComplete() {
        AsyncTask terminal = new AsyncTask();
        terminal.setType("EVAL_AGENT");
        when(taskMapper.selectById(1L)).thenReturn(terminal);
        when(taskMapper.markSuccess(eq(1L), anyString())).thenReturn(0);

        assertFalse(service.complete(1L, "{}"));
        verify(evalRunMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("评估任务完成：写入 metrics/detail/report_path")
    void evalRunCompleted() {
        AsyncTask task = new AsyncTask();
        task.setType("EVAL_RETRIEVAL");
        when(taskMapper.selectById(2L)).thenReturn(task);
        when(taskMapper.markSuccess(eq(2L), anyString())).thenReturn(1);

        String result = "{\"total\":10,\"passed\":8,\"metrics\":{\"MRR\":0.7},"
                + "\"detail\":[{\"id\":\"x\"}],\"report_path\":\"eval/reports/a.html\"}";
        assertTrue(service.complete(2L, result));
        verify(evalRunMapper).update(any(AiEvalRun.class), any());
    }

    @Test
    @DisplayName("幂等键命中 SUCCESS 任务 → 直接返回原任务，不重复入队")
    void idempotencyKeyReusesExistingTask() {
        AsyncTask existing = new AsyncTask();
        existing.setId(77L);
        existing.setStatus("SUCCESS");
        existing.setIdempotencyKey("k-1");
        when(taskMapper.selectByIdempotencyKey("k-1")).thenReturn(existing);

        AsyncTask result = service.submit("EVAL_RETRIEVAL", Map.of(), 1L, "k-1");

        assertEquals(77L, result.getId());
        verify(taskMapper, never()).insert(any(AsyncTask.class));
    }

    @Test
    @DisplayName("幂等键对应 FAILED 任务 → 仍允许重新入队")
    void failedTaskDoesNotReuse() {
        AsyncTask failed = new AsyncTask();
        failed.setId(78L);
        failed.setStatus("FAILED");
        when(taskMapper.selectByIdempotencyKey("k-2")).thenReturn(failed);
        when(taskMapper.selectActiveByType(anyString())).thenReturn(null);
        stubInsert(79L);

        AsyncTask result = service.submit("EVAL_RETRIEVAL", Map.of(), 1L, "k-2");

        assertEquals(79L, result.getId());
        verify(taskMapper).insert(any(AsyncTask.class));
    }
}
