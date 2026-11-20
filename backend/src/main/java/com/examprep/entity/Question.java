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

    /**
     * 真题年份。
     *
     * <p>由 {@code sql/migration-question-year.sql} 从 source 前 4 位回填（仅 '20xx年' 开头的来源），
     * 非真题或来源无法解析时为 null。空值语义 = 「不限年份 / 年份未知」，不是 「0 年」。
     */
    @Schema(description = "真题年份（非真题为 NULL）", example = "2023")
    private Integer year;

    /**
     * 考试类别编码（2026-11-11 由 sql/migration-question-exam-code.sql 新增）。
     *
     * <p>取值：CET4 / CET6 / GUOKAO / GUOKAO_SHENLUN / POSTGRAD_ENG1 / POSTGRAD_ENG2 /
     * POSTGRAD_MATH1 / POSTGRAD_MATH2 / POSTGRAD_MATH3 / INTERVIEW；历史数据为 NULL。
     * 用于「按考试类别筛选 / 组卷」，与 subject_id 并列而不是替代它。
     */
    @Schema(description = "考试类别编码", example = "CET4")
    private String examCode;

    @Schema(description = "试卷/资料来源名称", example = "2023.06英语四级真题第1套")
    private String paperName;

    @Schema(description = "导入来源文件路径", example = "D:/题库/...")
    private String originPath;

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
