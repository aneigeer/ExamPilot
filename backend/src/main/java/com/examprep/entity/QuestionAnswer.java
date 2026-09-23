package com.examprep.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName(value = "question_answer", autoResultMap = true)
@Schema(description = "题目答案（JSON 承载各题型结构）")
public class QuestionAnswer {

    @TableId(type = IdType.AUTO)
    private Long id;

    @Schema(description = "题目 ID")
    private Long questionId;

    /**
     * 答案内容，按题型存不同 JSON 结构：
     * 填空 {"blanks":["答案1"]}
     * 简答 {"reference":"参考答案","keywords":["关键词"]}
     * 作文 {"modelEssay":"范文","wordLimit":120}
     * 翻译 {"referenceTranslation":"译文","direction":"zh2en"}
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    @Schema(description = "答案内容（JSON）")
    private Object answerContent;

    @TableField(typeHandler = JacksonTypeHandler.class)
    @Schema(description = "评分要点（JSON 数组）")
    private Object gradingPoints;

    @Schema(description = "填空是否大小写敏感")
    private Integer caseSensitive;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
