package com.examprep.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("exam_paper")
@Schema(description = "试卷")
public class ExamPaper {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;
    private Long subjectId;

    @Schema(description = "考试时长（分钟）")
    private Integer durationMinutes;

    private Integer totalScore;
    private Integer questionCount;

    @Schema(description = "试卷类型：RANDOM 随机组卷 / OFFICIAL 官方套卷 / CUSTOM 自建套卷")
    private String paperType;

    @Schema(description = "考试类别编码：CET4/GUOKAO/POSTGRAD_*…；随机卷可为空")
    private String examCode;

    @Schema(description = "真题年份（非真题为 NULL）")
    private Integer year;

    @Schema(description = "是否发布：0 否，1 是（学生端只可见已发布的 OFFICIAL/CUSTOM 套卷）")
    private Integer published;

    @Schema(description = "试卷说明")
    private String description;

    private Long createdBy;
    private LocalDateTime createdAt;
}
