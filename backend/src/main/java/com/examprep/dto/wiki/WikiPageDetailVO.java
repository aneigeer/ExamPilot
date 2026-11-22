package com.examprep.dto.wiki;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Wiki 页面详情（正文 + 关联页导航）
 */
@Data
@Schema(description = "Wiki 页面详情")
public class WikiPageDetailVO {

    private Long id;

    private String slug;

    private String title;

    private Long subjectId;

    private Long knowledgeId;

    private String summary;

    @Schema(description = "Markdown 正文")
    private String content;

    private List<String> keyPoints;

    private List<Long> sourceChunkIds;

    private Integer version;

    private String trustTier;

    private String staleReason;

    private LocalDateTime expiresAt;

    private Double qualityScore;

    private LocalDateTime updatedAt;

    /** 相关页（出边 + 入边去重） */
    private List<WikiPageVO> relatedPages;
}
