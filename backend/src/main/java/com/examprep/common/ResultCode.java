package com.examprep.common;

import lombok.Getter;

/**
 * 统一状态码 / 错误码
 * 200-599 与 HTTP 语义对齐；1000+ 为业务码
 */
@Getter
public enum ResultCode {

    SUCCESS(200, "成功"),

    // ---------- 通用 ----------
    PARAM_ERROR(400, "参数错误"),
    UNAUTHORIZED(401, "未登录或登录已过期"),
    FORBIDDEN(403, "无权限访问"),
    NOT_FOUND(404, "资源不存在"),
    CONFLICT(409, "数据冲突或重复提交"),
    FILE_TOO_LARGE(413, "文件过大"),
    UNSUPPORTED_TYPE(415, "不支持的文件类型"),
    TOO_MANY_REQUESTS(429, "请求过于频繁"),
    SERVER_ERROR(500, "服务器内部错误"),

    // ---------- 认证与用户 ----------
    LOGIN_FAILED(1001, "学号或密码错误"),
    ACCOUNT_DISABLED(1002, "账号已被禁用"),
    TOKEN_INVALID(1003, "登录凭证无效"),
    OLD_PASSWORD_WRONG(1004, "原密码不正确"),
    USER_NOT_FOUND(1005, "用户不存在"),
    STUDENT_NO_EXISTS(1006, "该学号已注册"),

    // ---------- 题库与刷题 ----------
    QUESTION_NOT_FOUND(2001, "题目不存在"),
    QUESTION_TYPE_NOT_OPEN(2002, "该题型暂未开放"),
    PRACTICE_NOT_FOUND(2003, "练习记录不存在"),
    PRACTICE_FINISHED(2004, "该练习已结束"),

    // ---------- 资料库 ----------
    RESOURCE_NOT_FOUND(3001, "资料不存在"),
    RESOURCE_NOT_APPROVED(3002, "资料未通过审核"),
    UPLOAD_EMPTY(3003, "上传文件为空"),
    FILE_TYPE_NOT_ALLOWED(3004, "文件类型不允许，App 端仅支持图片"),
    FILE_DUPLICATE(3005, "相同文件已存在"),
    CLOUD_PREVIEW_LIMIT(3006, "游客仅可预览前 3 页，请登录后查看完整内容"),

    // ---------- 审核与举报 ----------
    AUDIT_SELF_FORBIDDEN(4001, "不能审核自己上传的内容"),
    AUDIT_STATUS_ILLEGAL(4002, "当前状态不允许该操作"),
    REPORT_ALREADY_HANDLED(4003, "该举报已处理"),

    // ---------- AI 导学（5xxx 段）----------
    AI_QUESTION_EMPTY(5001, "请输入你的问题"),
    AI_ANSWER_NOT_FOUND(5002, "未找到相关资料"),
    AI_LLM_UNAVAILABLE(5003, "AI 服务暂时不可用，请稍后重试"),
    AI_RETRIEVAL_FAILED(5004, "AI 检索失败，请稍后重试"),
    AI_REINDEX_FAILED(5005, "AI 索引重建失败"),
    AI_PROFILE_FAILED(5006, "学情画像生成失败"),
    AI_RECOMMEND_FAILED(5007, "推荐生成失败"),
    AI_SERVICE_UNAVAILABLE(503, "AI 服务暂时不可用，请使用题库与搜索功能"),

    // ---------- AI 微服务扩展（T09/T10/T11；52xx 段由 FastAPI 侧使用）----------
    AI_FASTAPI_UNAVAILABLE(5011, "AI 微服务不可用，已降级为基础检索"),
    AI_FASTAPI_TIMEOUT(5012, "AI 微服务响应超时，已降级为基础检索"),
    AI_VECTOR_SEARCH_FAILED(5013, "向量检索失败，已降级为全文检索"),
    AI_EMBEDDING_FAILED(5014, "向量化失败，已降级为全文检索"),
    AI_VECTOR_INDEX_STALE(5015, "向量索引与当前模型不匹配，需重建"),
    AI_INDEX_SYNC_PENDING(5016, "向量索引待同步，检索结果可能不完整"),
    AI_STORAGE_UNAVAILABLE(5017, "对象存储不可用，已回落本地磁盘"),
    AI_OCR_UNAVAILABLE(5018, "OCR 不可用，请手动录入"),
    AI_OCR_RECOGNIZE_FAILED(5019, "OCR 识别失败，请手动录入"),
    AI_OCR_STRUCTURE_FAILED(5020, "OCR 结构化失败，原图已归档，请人工补录");

    private final Integer code;
    private final String message;

    ResultCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }
}
