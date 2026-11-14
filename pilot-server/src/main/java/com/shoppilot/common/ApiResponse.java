package com.shoppilot.common;

/**
 * 统一响应包装。对应 docs/api-contract.md 第 6 节：
 * { "code": 0, "message": "ok", "data": {}, "traceId": "..." }
 * <p>
 * code == 0 表示成功，非 0 为业务错误码（见 {@link ErrorCode}）。
 */
public record ApiResponse<T>(int code, String message, T data, String traceId) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(ErrorCode.OK.code(), "ok", data, null);
    }

    public static <T> ApiResponse<T> ok(T data, String traceId) {
        return new ApiResponse<>(ErrorCode.OK.code(), "ok", data, traceId);
    }

    public static <T> ApiResponse<T> fail(ErrorCode errorCode, String message, String traceId) {
        return new ApiResponse<>(errorCode.code(), message, null, traceId);
    }
}
