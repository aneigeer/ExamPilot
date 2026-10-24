package com.examprep.dto.ai;

import com.examprep.entity.AsyncTask;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 异步任务列表项（不返回 payload/result 大字段）
 */
@Data
@Schema(description = "异步任务列表项")
public class AsyncTaskVO {

    private Long id;
    private String type;
    private String stream;
    private String status;
    private Integer progress;
    private String error;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    public static AsyncTaskVO of(AsyncTask task) {
        AsyncTaskVO vo = new AsyncTaskVO();
        vo.setId(task.getId());
        vo.setType(task.getType());
        vo.setStream(task.getStream());
        vo.setStatus(task.getStatus());
        vo.setProgress(task.getProgress());
        vo.setError(task.getError());
        vo.setCreatedBy(task.getCreatedBy());
        vo.setCreatedAt(task.getCreatedAt());
        vo.setStartedAt(task.getStartedAt());
        vo.setFinishedAt(task.getFinishedAt());
        return vo;
    }
}
