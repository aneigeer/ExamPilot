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
@TableName("subject")
@Schema(description = "科目")
public class Subject {

    @TableId(type = IdType.AUTO)
    private Long id;

    @Schema(description = "所属考试类别 ID")
    private Long examCategoryId;

    @Schema(description = "科目名称", example = "高等数学A")
    private String name;

    @Schema(description = "科目说明")
    private String description;

    @Schema(description = "题目数（冗余统计）")
    private Integer questionCount;

    @Schema(description = "资料数（冗余统计）")
    private Integer resourceCount;

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
