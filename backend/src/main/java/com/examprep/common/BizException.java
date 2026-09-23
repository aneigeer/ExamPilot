package com.examprep.common;

import lombok.Getter;

/**
 * 业务异常：抛出后由 GlobalExceptionHandler 统一转为 Result
 */
@Getter
public class BizException extends RuntimeException {

    private final Integer code;

    public BizException(ResultCode rc) {
        super(rc.getMessage());
        this.code = rc.getCode();
    }

    public BizException(ResultCode rc, String message) {
        super(message);
        this.code = rc.getCode();
    }

    public BizException(String message) {
        super(message);
        this.code = ResultCode.PARAM_ERROR.getCode();
    }
}
