package com.examprep.dto;

import com.examprep.entity.KnowledgePoint;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识点树节点
 */
@Data
@Schema(description = "知识点树节点")
public class KnowledgeNodeVO {

    @Schema(description = "知识点 ID")
    private Long id;

    @Schema(description = "父节点 ID，0 为根")
    private Long parentId;

    @Schema(description = "名称")
    private String name;

    @Schema(description = "层级：1 章节 / 2 知识点 / 3 考点")
    private Integer level;

    @Schema(description = "排序值")
    private Integer sort;

    @Schema(description = "子节点")
    private List<KnowledgeNodeVO> children = new ArrayList<>();

    public static KnowledgeNodeVO from(KnowledgePoint kp) {
        KnowledgeNodeVO vo = new KnowledgeNodeVO();
        vo.setId(kp.getId());
        vo.setParentId(kp.getParentId());
        vo.setName(kp.getName());
        vo.setLevel(kp.getLevel());
        vo.setSort(kp.getSort());
        return vo;
    }
}
