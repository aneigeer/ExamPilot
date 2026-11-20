package com.examprep.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 科目覆盖矩阵快照（ADS 层）
 *
 * <p>唯一键 {@code (exam_code, subject_id)}；{@code exam_code} 用空串表示未归类，
 * 避免 MySQL 唯一键对 NULL 不去重的坑。
 */
@Data
@TableName("ads_subject_coverage")
@Schema(description = "科目覆盖矩阵")
public class AdsSubjectCoverage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String examCode;
    private Long subjectId;
    private String subjectName;
    private Integer liveQuestionCount;
    private Integer approvedQuestionCount;
    private Integer pendingQuestionCount;
    private Integer chunkActiveCount;
    private Integer coursewareCount;
    private Integer knowledgeCount;
    private BigDecimal coverageScore;
    private LocalDateTime updatedAt;
}
