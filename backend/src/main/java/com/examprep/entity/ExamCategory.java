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
@TableName("exam_category")
@Schema(description = "考试类别")
public class ExamCategory {

    @TableId(type = IdType.AUTO)
    private Long id;

    @Schema(description = "编码：SCHOOL / CET4 / CET6 / NCRE", example = "CET4")
    private String code;

    @Schema(description = "考试类别名称", example = "英语四级")
    private String name;

    @Schema(description = "类型：SCHOOL 校内课程 / CERT 证书考试")
    private String type;

    @Schema(description = "图标地址")
    private String icon;

    @Schema(description = "说明")
    private String description;

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
