package com.examprep.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spring 内建 4xx 不再被 catch-all 吞成 500。
 *
 * <p>押点：{@code ExceptionHandlerExceptionResolver} 优先级高于
 * {@code ResponseStatusExceptionResolver} / {@code DefaultHandlerExceptionResolver}，
 * 若 {@code @ExceptionHandler(Exception.class)} 兜底是最具体的匹配，
 * {@code NoResourceFoundException}(404)、{@code HttpRequestMethodNotSupportedException}(405)、
 * {@code HttpMediaTypeNotAcceptableException}(406) 全会被改写成 500。
 * 本测试用 MockMvc 走真实的「异常 → 处理器选择」链路，断言 HTTP 状态与 body code 同步，
 * 且 {@code Exception} 兜底对真异常仍是 500（负向不误伤）。
 */
@DisplayName("GlobalExceptionHandler：Spring 内建 4xx 映射 + 兜底 500")
class GlobalExceptionHandlerTest {

    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new ThrowingController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    @DisplayName("NoResourceFoundException（GET /api/me 无映射）→ HTTP 404 / code 404")
    void noResourceFoundIs404() throws Exception {
        mvc.perform(get("/test/no-resource"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value(containsString("接口不存在")))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    @DisplayName("NoHandlerFoundException → HTTP 404 / code 404")
    void noHandlerFoundIs404() throws Exception {
        mvc.perform(get("/test/no-handler"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value(containsString("接口不存在")));
    }

    @Test
    @DisplayName("HttpRequestMethodNotSupportedException → HTTP 405 / code 405，且回填 Allow")
    void methodNotSupportedIs405() throws Exception {
        mvc.perform(get("/test/method-not-supported"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string(HttpHeaders.ALLOW, "POST"))
                .andExpect(jsonPath("$.code").value(405))
                .andExpect(jsonPath("$.message").value(containsString("不支持 GET")));
    }

    @Test
    @DisplayName("HttpMediaTypeNotAcceptableException → HTTP 406 / code 406")
    void mediaTypeNotAcceptableIs406() throws Exception {
        mvc.perform(get("/test/not-acceptable"))
                .andExpect(status().isNotAcceptable())
                .andExpect(jsonPath("$.code").value(406));
    }

    @Test
    @DisplayName("真实 406（Accept: text/html）→ 仍是 406，错误体保持统一 Result 结构")
    void realNotAcceptableStill406() throws Exception {
        mvc.perform(get("/test/json").accept(MediaType.TEXT_HTML))
                .andExpect(status().isNotAcceptable())
                .andExpect(jsonPath("$.code").value(406))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    @DisplayName("ResponseStatusException → 按其自身 status，不降级为 500")
    void responseStatusExceptionKeepsOwnStatus() throws Exception {
        mvc.perform(get("/test/response-status"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.message").value("AI 服务暂时不可用"));
    }

    @Test
    @DisplayName("未预期异常仍走兜底 → HTTP 500 / code 500（负向不误伤）")
    void unexpectedExceptionIs500() throws Exception {
        mvc.perform(get("/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    /** 每个端点抛一种异常，用于验证「最具体 handler 优先于 catch-all」 */
    @RestController
    static class ThrowingController {

        @GetMapping("/test/no-resource")
        void noResource() throws NoResourceFoundException {
            throw new NoResourceFoundException(HttpMethod.GET, "/api/me");
        }

        @GetMapping("/test/no-handler")
        void noHandler() throws NoHandlerFoundException {
            throw new NoHandlerFoundException("GET", "/api/me", new HttpHeaders());
        }

        @GetMapping("/test/method-not-supported")
        void methodNotSupported() throws HttpRequestMethodNotSupportedException {
            throw new HttpRequestMethodNotSupportedException("GET", List.of("POST"));
        }

        @GetMapping("/test/not-acceptable")
        void notAcceptable() throws HttpMediaTypeNotAcceptableException {
            throw new HttpMediaTypeNotAcceptableException("不接受该 Accept 类型");
        }

        @GetMapping("/test/response-status")
        void responseStatus() {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI 服务暂时不可用");
        }

        @GetMapping("/test/boom")
        void boom() {
            throw new IllegalStateException("模拟未预期异常");
        }

        /** 正常返回 JSON，用于制造真实的内容协商 406（Accept: text/html） */
        @GetMapping("/test/json")
        Result<String> json() {
            return Result.success("ok");
        }
    }
}
