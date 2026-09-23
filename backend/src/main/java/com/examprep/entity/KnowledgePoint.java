package com.examprep.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("knowledge_point")
@Schema(description = "知识点（树形）")
public class KnowledgePoint {

    @TableId(type = IdType.AUTO)
    private Long id;

    @Schema(description = "所属科目 ID")
    private Long subjectId;

    @Schema(description = "父知识点 ID，0 为根节点")
    private Long parentId;

    @Schema(description = "知识点名称", example = "递归")
    private String name;

    @Schema(description = "层级：1 章节 / 2 知识点 / 3 考点")
    private Integer level;

    @Schema(description = "排序值")
    private Integer sort;

    @Schema(description = "状态：1 启用，0 停用")
    private Integer status;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic
    @JsonIgnore
    private Integer isDeleted;
}
