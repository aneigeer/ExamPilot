package com.examprep.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "题目笔记")
public class QuestionNoteVO {

    private Long id;
    private Long questionId;
    private Long noteId;

    @Schema(description = "题型中文名")
    private String typeName;

    @Schema(description = "题干（截断）")
    private String stem;

    @Schema(description = "笔记内容")
    private String content;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
