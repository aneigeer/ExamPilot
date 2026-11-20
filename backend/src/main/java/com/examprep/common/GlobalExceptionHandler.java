package com.examprep.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 全局异常处理：所有异常统一转为 {code,message,data,traceId}
 * <p>
 * 约定：业务码落在 400-599 HTTP 区间时必须同步 HTTP 状态码
 * （客户端按 HTTP 状态码分支判断成败，HTTP 200 会被误判为成功）。
 * MethodArgumentNotValidException 在 Spring 6 中继承 BindException，由 handleValid 统一处理。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * E1-04：认证失败类业务码 → HTTP 状态映射。
     * 只改 HTTP 状态，响应体里的业务码（1001/1002/…）保持不变，
     * 前端按 {@code json.code} / {@code ApiResult.ok()} 判成败的逻辑不受影响；
     * 同时让网关按 4xx 统计的登录爆破告警不再漏报。
     */
    private static final Map<Integer, Integer> AUTH_FAILURE_HTTP_STATUS = Map.of(
            ResultCode.LOGIN_FAILED.getCode(), HttpServletResponse.SC_UNAUTHORIZED,
            ResultCode.ACCOUNT_DISABLED.getCode(), HttpServletResponse.SC_UNAUTHORIZED,
            ResultCode.TOKEN_INVALID.getCode(), HttpServletResponse.SC_UNAUTHORIZED,
            ResultCode.OLD_PASSWORD_WRONG.getCode(), HttpStatus.BAD_REQUEST.value());

    /** 业务异常（可预期，记 warn 即可）。业务码落在 400-599 HTTP 区间时同步 HTTP 状态码（如 409 重复注册） */
    @ExceptionHandler(BizException.class)
    public Result<Void> handleBiz(BizException e, HttpServletRequest req, HttpServletResponse resp) {
        log.warn("业务异常 [{} {}] {}", req.getMethod(), req.getRequestURI(), e.getMessage());
        // E1-04：认证失败类业务码映射为 401/400；其余业务码沿用原逻辑
        Integer httpStatus = AUTH_FAILURE_HTTP_STATUS.get(e.getCode());
        setStatusIfHttpRange(resp, httpStatus == null ? e.getCode() : httpStatus);
        return Result.fail(e.getCode(), e.getMessage());
    }

    /** 参数校验失败（@Valid / @Validated，含 MethodArgumentNotValidException）→ HTTP 400 */
    @ExceptionHandler(BindException.class)
    public Result<Void> handleValid(BindException e, HttpServletResponse resp) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("；"));
        log.warn("参数校验失败: {}", msg);
        setStatusIfHttpRange(resp, ResultCode.PARAM_ERROR.getCode());
        return Result.fail(ResultCode.PARAM_ERROR, msg);
    }

    /** 缺少请求参数 → HTTP 400 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException e, HttpServletResponse resp) {
        setStatusIfHttpRange(resp, ResultCode.PARAM_ERROR.getCode());
        return Result.fail(ResultCode.PARAM_ERROR, "缺少必要参数：" + e.getParameterName());
    }

    /** 参数类型不匹配 → HTTP 400 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e, HttpServletResponse resp) {
        setStatusIfHttpRange(resp, ResultCode.PARAM_ERROR.getCode());
        return Result.fail(ResultCode.PARAM_ERROR, "参数类型不正确：" + e.getName());
    }

    /** 上传文件超限 → HTTP 413 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Result<Void> handleUploadSize(MaxUploadSizeExceededException e, HttpServletResponse resp) {
        log.warn("上传文件超限: {}", e.getMessage());
        setStatusIfHttpRange(resp, ResultCode.FILE_TOO_LARGE.getCode());
        return Result.fail(ResultCode.FILE_TOO_LARGE);
    }

    /** 唯一键冲突（如并发注册同学号）→ HTTP 409 */
    @ExceptionHandler(DuplicateKeyException.class)
    public Result<Void> handleDuplicateKey(DuplicateKeyException e, HttpServletRequest req, HttpServletResponse resp) {
        log.warn("唯一键冲突 [{} {}]", req.getMethod(), req.getRequestURI());
        setStatusIfHttpRange(resp, ResultCode.CONFLICT.getCode());
        return Result.fail(ResultCode.CONFLICT.getCode(), "数据已存在");
    }

    /** 请求体缺失或 JSON 格式错误（如空 body 登录）→ HTTP 400 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleNotReadable(HttpMessageNotReadableException e, HttpServletRequest req,
                                          HttpServletResponse resp) {
        log.warn("请求体不可读 [{} {}]", req.getMethod(), req.getRequestURI());
        setStatusIfHttpRange(resp, ResultCode.PARAM_ERROR.getCode());
        return Result.fail(ResultCode.PARAM_ERROR.getCode(), "请求体格式错误");
    }

    /** multipart 上传缺少必填 part（如不带文件字段）→ HTTP 400 */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public Result<Void> handleMissingPart(MissingServletRequestPartException e, HttpServletResponse resp) {
        setStatusIfHttpRange(resp, ResultCode.PARAM_ERROR.getCode());
        return Result.fail(ResultCode.PARAM_ERROR.getCode(), "缺少必填字段：" + e.getRequestPartName());
    }

    /** Content-Type 不支持（如 POST 登录带 text/plain）→ HTTP 400 */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public Result<Void> handleMediaType(HttpMediaTypeNotSupportedException e, HttpServletResponse resp) {
        setStatusIfHttpRange(resp, ResultCode.PARAM_ERROR.getCode());
        return Result.fail(ResultCode.PARAM_ERROR.getCode(), "Content-Type 不受支持，请使用 application/json");
    }

    /** multipart 请求整体格式错误（如完全不带 multipart body）→ HTTP 400 */
    @ExceptionHandler(MultipartException.class)
    public Result<Void> handleMultipart(MultipartException e, HttpServletResponse resp) {
        setStatusIfHttpRange(resp, ResultCode.PARAM_ERROR.getCode());
        return Result.fail(ResultCode.PARAM_ERROR.getCode(), "上传请求格式错误");
    }

    /** 无权限（@PreAuthorize 拦截）→ HTTP 403 */
    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Result<Void> handleAccessDenied(AccessDeniedException e) {
        return Result.fail(ResultCode.FORBIDDEN);
    }

    /** 认证失败 → HTTP 401 */
    @ExceptionHandler(AuthenticationException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Result<Void> handleAuth(AuthenticationException e) {
        return Result.fail(ResultCode.UNAUTHORIZED);
    }

    /** 无映射路由 / 缺失静态资源 → HTTP 404（Spring 6.1+ 资源处理器抛出，禁止落入兜底 500） */
    @ExceptionHandler(NoResourceFoundException.class)
    public Result<Void> handleNoResource(NoResourceFoundException e, HttpServletRequest req,
                                         HttpServletResponse resp) {
        String method = e.getHttpMethod() != null ? e.getHttpMethod().name() : req.getMethod();
        log.warn("接口不存在 [{} {}]", method, req.getRequestURI());
        setStatusIfHttpRange(resp, ResultCode.NOT_FOUND.getCode());
        return Result.fail(ResultCode.NOT_FOUND.getCode(), "接口不存在：" + method + " " + req.getRequestURI());
    }

    /** 无匹配 handler（需 throwExceptionIfNoHandlerFound=true）→ HTTP 404 */
    @ExceptionHandler(NoHandlerFoundException.class)
    public Result<Void> handleNoHandler(NoHandlerFoundException e, HttpServletResponse resp) {
        log.warn("接口不存在 [{} {}]", e.getHttpMethod(), e.getRequestURL());
        setStatusIfHttpRange(resp, ResultCode.NOT_FOUND.getCode());
        return Result.fail(ResultCode.NOT_FOUND.getCode(),
                "接口不存在：" + e.getHttpMethod() + " " + e.getRequestURL());
    }

    /** 请求方法不支持 → HTTP 405，并回填 Allow 响应头 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Result<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e,
                                                 HttpServletRequest req, HttpServletResponse resp) {
        String[] supported = e.getSupportedMethods();
        if (supported != null && supported.length > 0) {
            resp.setHeader(HttpHeaders.ALLOW, String.join(", ", supported));
        }
        log.warn("请求方法不支持 [{} {}]，允许: {}", req.getMethod(), req.getRequestURI(),
                supported == null ? "无" : String.join(",", supported));
        setStatusIfHttpRange(resp, HttpStatus.METHOD_NOT_ALLOWED.value());
        return Result.fail(HttpStatus.METHOD_NOT_ALLOWED.value(), "该接口不支持 " + req.getMethod() + " 请求");
    }

    /** 客户端 Accept 与可产出类型不匹配 → HTTP 406 */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public Result<Void> handleNotAcceptable(HttpMediaTypeNotAcceptableException e, HttpServletRequest req,
                                            HttpServletResponse resp) {
        log.warn("Accept 类型不匹配 [{} {}]", req.getMethod(), req.getRequestURI());
        // 客户端 Accept 可能不含 json，预设 Content-Type 让错误体仍以统一结构写出（否则二次协商失败、body 为空）
        resp.setContentType(MediaType.APPLICATION_JSON_VALUE);
        setStatusIfHttpRange(resp, HttpStatus.NOT_ACCEPTABLE.value());
        return Result.fail(HttpStatus.NOT_ACCEPTABLE.value(), "不接受该 Accept 类型，请使用 application/json");
    }

    /** 自带状态码的异常（三方库/未来代码抛出）→ 按其自身 status，不再一律 500 */
    @ExceptionHandler(ResponseStatusException.class)
    public Result<Void> handleResponseStatus(ResponseStatusException e, HttpServletRequest req,
                                             HttpServletResponse resp) {
        int status = e.getStatusCode().value();
        String reason = e.getReason();
        log.warn("请求被拒绝 [{} {}] status={} reason={}", req.getMethod(), req.getRequestURI(), status, reason);
        resp.setStatus(status);
        return Result.fail(status, reason != null && !reason.isBlank() ? reason : "请求处理失败");
    }

    /** 兜底：未预期异常，必须打完整堆栈 → HTTP 500 */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleOther(Exception e, HttpServletRequest req, HttpServletResponse resp) {
        log.error("系统异常 [{} {}]", req.getMethod(), req.getRequestURI(), e);
        setStatusIfHttpRange(resp, ResultCode.SERVER_ERROR.getCode());
        return Result.fail(ResultCode.SERVER_ERROR);
    }

    /** 仅当业务码处于 HTTP 400-599 区间时同步 HTTP 状态码，业务码（1000+）保持 HTTP 200 */
    private void setStatusIfHttpRange(HttpServletResponse resp, Integer code) {
        if (code != null && code >= 400 && code <= 599) {
            resp.setStatus(code);
        }
    }
}
