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
    REPORT_ALREADY_HANDLED(4003, "该举报已处理");

    private final Integer code;
    private final String message;

    ResultCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }
}
