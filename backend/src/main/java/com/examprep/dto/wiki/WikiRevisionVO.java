package com.examprep.dto.wiki;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Wiki 草稿修订（管理端审核用）
 */
@Data
@Schema(description = "Wiki 草稿修订")
public class WikiRevisionVO {

    private Long id;

    private Long pageId;

    private String slug;

    private String title;

    private Long subjectId;

    private Long knowledgeId;

    private String summary;

    private String content;

    private List<String> keyPoints;

    private List<Long> sourceChunkIds;

    private String model;

    private Double qualityScore;

    private String status;

    private Long taskId;

    private Long reviewerId;

    private String reviewNote;

    private LocalDateTime createdAt;

    private LocalDateTime reviewedAt;
}
