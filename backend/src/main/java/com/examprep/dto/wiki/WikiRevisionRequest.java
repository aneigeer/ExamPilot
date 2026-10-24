package com.examprep.dto.wiki;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.util.List;

/**
 * Python 蒸馏结果 → Java 草稿修订（内部接口契约，snake_case）
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class WikiRevisionRequest {

    /** 稳定标识，如 kp-5 */
    private String slug;

    private String title;

    private Long subjectId;

    private Long knowledgeId;

    private String summary;

    /** Markdown 正文 */
    private String content;

    private List<String> keyPoints;

    /** 必须来自蒸馏输入块的 ID（Python 侧已校验，Java 再兜底一次） */
    private List<Long> sourceChunkIds;

    /** LLM 提议的相关页链接 */
    private List<WikiLinkRequest> links;

    private String model;

    /** 0-1 质量分 */
    private Double qualityScore;

    /** 来源异步任务 ID */
    private Long taskId;

    @Data
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class WikiLinkRequest {
        private String toSlug;
        private String relation;
        private Double weight;
    }
}
