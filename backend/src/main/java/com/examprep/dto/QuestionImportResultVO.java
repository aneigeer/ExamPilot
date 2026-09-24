package com.examprep.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "题目批量导入结果")
public class QuestionImportResultVO {

    @Schema(description = "总条数")
    private Integer total;

    @Schema(description = "导入成功数")
    private Integer success;

    @Schema(description = "失败数")
    private Integer failed;

    @Schema(description = "失败明细（第 N 条：原因）")
    private List<String> errors;
}
