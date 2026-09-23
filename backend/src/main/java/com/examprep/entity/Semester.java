package com.examprep.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("semester")
@Schema(description = "学期")
public class Semester {

    @TableId(type = IdType.AUTO)
    private Long id;

    @Schema(description = "学期名称", example = "2024-2025-2")
    private String name;

    private LocalDate startDate;
    private LocalDate endDate;

    @Schema(description = "是否当前学期")
    private Integer isCurrent;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic
    @JsonIgnore
    private Integer isDeleted;
}
