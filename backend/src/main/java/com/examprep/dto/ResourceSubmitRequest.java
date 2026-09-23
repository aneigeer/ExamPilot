package com.examprep.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "提交资料请求")
public class ResourceSubmitRequest {

    @NotBlank(message = "标题不能为空")
    @Schema(description = "资料标题", requiredMode = Schema.RequiredMode.REQUIRED)
    private String title;

    @NotNull(message = "考试类别不能为空")
    private Long examCategoryId;

    @Schema(description = "科目 ID")
    private Long subjectId;

    @Schema(description = "知识点 ID")
    private Long knowledgeId;

    @Schema(description = "关联课程 ID（校内课程）")
    private Long courseId;

    @NotBlank(message = "资料类型不能为空")
    @Schema(description = "类型：COURSEWARE / NOTE / SUMMARY / MOCK / RECALL / ORIGINAL",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "MOCK")
    private String type;

    @Schema(description = "简介")
    private String description;

    @NotNull(message = "请先上传文件")
    @Schema(description = "已上传文件的 ID（由 /files/image 或 /files/document 返回）",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private Long fileId;
}
