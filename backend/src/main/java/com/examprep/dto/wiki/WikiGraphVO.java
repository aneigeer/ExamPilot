package com.examprep.dto.wiki;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Wiki 导航图（节点 + 有向边）
 */
@Schema(description = "Wiki 导航图")
public record WikiGraphVO(List<Node> nodes, List<Edge> edges) {

    @Schema(description = "图谱节点")
    public record Node(Long id, String slug, String title, Long subjectId,
                       Long knowledgeId, double qualityScore, int degree) {
    }

    @Schema(description = "图谱边")
    public record Edge(Long source, Long target, String relation, double weight) {
    }
}
