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
@TableName("question")
@Schema(description = "题目（全题型统一存储）")
public class Question {

    @TableId(type = IdType.AUTO)
    private Long id;

    @Schema(description = "所属科目 ID")
    private Long subjectId;

    @Schema(description = "题型枚举", example = "SINGLE_CHOICE")
    private String type;

    @Schema(description = "题干")
    private String stem;

    @Schema(description = "材料正文（组合题 / 阅读题用）")
    private String material;

    @Schema(description = "难度 1-5")
    private Integer difficulty;

    @Schema(description = "来源")
    private String source;

    @Schema(description = "标签，逗号分隔")
    private String tags;

    @Schema(description = "解析")
    private String analysis;

    @Schema(description = "分值")
    private Integer score;

    @Schema(description = "父题 ID，0 为独立题")
    private Long parentId;

    @Schema(description = "子题排序")
    private Integer sort;

    @Schema(description = "审核状态")
    private String auditStatus;

    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic
    @JsonIgnore
    private Integer isDeleted;
}
