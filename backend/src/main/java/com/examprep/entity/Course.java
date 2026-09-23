package com.examprep.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("course")
@Schema(description = "课程 / 开课班")
public class Course {

    @TableId(type = IdType.AUTO)
    private Long id;

    @Schema(description = "课程名称")
    private String name;

    @Schema(description = "关联科目 ID")
    private Long subjectId;

    @Schema(description = "授课教师 ID")
    private Long teacherId;

    @Schema(description = "所属学院 ID")
    private Long collegeId;

    @Schema(description = "适用专业 ID")
    private Long majorId;

    @Schema(description = "开课学期 ID")
    private Long semesterId;

    @Schema(description = "班级名称")
    private String className;

    @Schema(description = "学分")
    private BigDecimal credit;

    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic
    @JsonIgnore
    private Integer isDeleted;
}
