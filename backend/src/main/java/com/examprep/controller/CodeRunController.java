package com.examprep.controller;

import com.examprep.common.Result;
import com.examprep.dto.CodeRunRequest;
import com.examprep.dto.CodeRunResultVO;
import com.examprep.security.SecurityUtils;
import com.examprep.service.CodeRunService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 编程题在线运行（P1）
 */
@Tag(name = "21. 编程题运行", description = "在线编译运行编程题代码（本机使用用）")
@RestController
@RequestMapping("/run")
@RequiredArgsConstructor
public class CodeRunController {

    private final CodeRunService codeRunService;

    @Operation(summary = "在线运行代码",
            description = """
                    **访问级别：AUTH**。支持 **C**（gcc）与 **Java**（JDK 11+ 单文件运行，公共类名需与类定义一致）。

                    - 题目的测试输入与期望输出存于答案 JSON：`{"input": "...", "expectedOutput": "..."}`
                    - 返回实际输出、错误信息、是否与期望输出一致
                    - 超过 10 秒强制终止
                    - ⚠️ 该功能直接在本机执行用户代码，仅适合本机单人使用，公网部署必须换独立判题沙箱
                    """)
    @PostMapping
    public Result<CodeRunResultVO> run(@Valid @RequestBody CodeRunRequest request) {
        Long userId = SecurityUtils.requireUserId();
        return Result.success(codeRunService.run(userId, request.getQuestionId(),
                request.getLanguage(), request.getCode()));
    }
}
