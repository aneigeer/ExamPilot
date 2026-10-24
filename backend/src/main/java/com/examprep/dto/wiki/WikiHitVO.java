package com.examprep.dto.wiki;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * Wiki 热层检索命中（Agent 工具与观测用）
 */
@Data
@Schema(description = "Wiki 检索命中")
public class WikiHitVO {

    private Long id;

    private String slug;

    private String title;

    private Long subjectId;

    private Long knowledgeId;

    private String summary;

    /** 正文（Agent 定稿时作为上下文；热层问答不经过此 VO） */
    private String content;

    private List<String> keyPoints;

    private double score;

    @Schema(description = "命中通道：VECTOR / NGRAM / LINK")
    private String channel;
}
