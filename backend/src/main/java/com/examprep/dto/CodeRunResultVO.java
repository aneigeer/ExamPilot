package com.examprep.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
@Schema(description = "代码运行结果")
public class CodeRunResultVO {

    @Schema(description = "标准输出")
    private String output;

    @Schema(description = "编译 / 运行错误信息")
    private String error;

    @Schema(description = "期望输出（题目未配置则为 null）")
    private String expectedOutput;

    @Schema(description = "实际输出是否与期望输出一致（去首尾空白后比较）")
    private Boolean matched;

    @Schema(description = "是否因本机缺少编译器而未能运行")
    private Boolean compilerMissing;

    @Schema(description = "耗时（毫秒）")
    private Long costMs;
}
