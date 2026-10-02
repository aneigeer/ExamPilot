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
@TableName("college")
@Schema(description = "学院")
public class College {

    @TableId(type = IdType.AUTO)
    private Long id;

    @Schema(description = "所属学校 ID")
    private Long schoolId;

    @Schema(description = "学院名称", example = "计算机学院")
    private String name;

    @Schema(description = "学院编码")
    private String code;

    private Integer sort;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic
    @JsonIgnore
    private Integer isDeleted;
}
