package com.examprep.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("question_option")
@Schema(description = "题目选项")
public class QuestionOption {

    @TableId(type = IdType.AUTO)
    private Long id;

    @Schema(description = "题目 ID")
    private Long questionId;

    @Schema(description = "选项标识：A/B/C/D 或 1/2/3", example = "A")
    private String optionKey;

    @Schema(description = "选项内容")
    private String content;

    @Schema(description = "排序")
    private Integer sort;

    @Schema(description = "是否正确答案（返回给前端前会按场景过滤）")
    private Integer isAnswer;

    private LocalDateTime createdAt;
}
