package com.examprep.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("practice_record")
@Schema(description = "练习记录")
public class PracticeRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    @Schema(description = "用户 ID")
    private Long userId;

    @Schema(description = "科目 ID")
    private Long subjectId;

    @Schema(description = "知识点 ID")
    private Long knowledgeId;

    @Schema(description = "练习模式：SEQUENCE / RANDOM / BY_TYPE / WRONG_BOOK")
    private String mode;

    @Schema(description = "按题型练习时的题型")
    private String questionType;

    @Schema(description = "总题数")
    private Integer totalCount;

    @Schema(description = "已答题数")
    private Integer answeredCount;

    @Schema(description = "正确数（主观题按自评折算后取整）")
    private Integer correctCount;

    @Schema(description = "状态：ONGOING / FINISHED")
    private String status;

    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
