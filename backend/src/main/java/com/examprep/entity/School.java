package com.examprep.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 学校实体，对应表 school
 */
@Data
@TableName("school")
@Schema(description = "学校")
public class School {

    @TableId(type = IdType.AUTO)
    @Schema(description = "主键")
    private Long id;

    @Schema(description = "学校名称", example = "中南林业科技大学")
    private String name;

    @Schema(description = "学校编码", example = "CSUFT")
    private String code;

    @Schema(description = "排序值，越小越靠前")
    private Integer sort;

    @Schema(description = "状态：1 正常，0 禁用")
    private Integer status;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

    @TableLogic
    @JsonIgnore
    private Integer isDeleted;
}
