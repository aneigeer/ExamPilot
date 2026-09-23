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
@TableName("teacher")
@Schema(description = "教师")
public class Teacher {

    @TableId(type = IdType.AUTO)
    private Long id;

    @Schema(description = "教师姓名", example = "王老师")
    private String name;

    @Schema(description = "所属学院 ID")
    private Long collegeId;

    @Schema(description = "职称")
    private String title;

    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic
    @JsonIgnore
    private Integer isDeleted;
}
