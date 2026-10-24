package com.examprep.dto.wiki;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Wiki 页面列表项
 */
@Data
@Schema(description = "Wiki 主题页")
public class WikiPageVO {

    private Long id;

    private String slug;

    private String title;

    private Long subjectId;

    private Long knowledgeId;

    private String summary;

    private List<String> keyPoints;

    private List<Long> sourceChunkIds;

    private Integer version;

    private Double qualityScore;

    private LocalDateTime updatedAt;
}
