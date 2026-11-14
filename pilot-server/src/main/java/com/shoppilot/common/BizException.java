package com.shoppilot.common;

/**
 * 业务异常。Service 层只抛它，Controller 层不写 try-catch，
 * 统一由 {@link GlobalExceptionHandler} 转成 ApiResponse。
 */
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        super(errorCode.defaultMessage());
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public static BizException notFound(String what) {
        return new BizException(ErrorCode.NOT_FOUND, what + "不存在");
    }

    public static BizException param(String message) {
        return new BizException(ErrorCode.PARAM_INVALID, message);
    }
}
