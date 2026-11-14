package com.shoppilot.common;

import org.springframework.http.HttpStatus;

/**
 * 业务错误码。对外暴露的 code 与 api-contract.md 的错误码约定保持一致，
 * 同时带上 HTTP 状态，避免所有错误都返回 200。
 */
public enum ErrorCode {

    OK(0, "ok", HttpStatus.OK),

    // 通用
    PARAM_INVALID(1001, "参数不合法", HttpStatus.BAD_REQUEST),
    UNAUTHORIZED(1002, "未登录或登录已失效", HttpStatus.UNAUTHORIZED),
    FORBIDDEN(1003, "无权访问该资源", HttpStatus.FORBIDDEN),
    NOT_FOUND(1004, "资源不存在", HttpStatus.NOT_FOUND),
    CONFLICT(1005, "资源状态冲突", HttpStatus.CONFLICT),
    RATE_LIMITED(1006, "请求过于频繁", HttpStatus.TOO_MANY_REQUESTS),

    // 交易
    STOCK_NOT_ENOUGH(2001, "库存不足", HttpStatus.CONFLICT),
    CART_EMPTY(2002, "购物车为空", HttpStatus.BAD_REQUEST),
    PRODUCT_OFF_SHELF(2003, "商品已下架", HttpStatus.CONFLICT),
    ORDER_STATUS_INVALID(2004, "订单状态不允许该操作", HttpStatus.CONFLICT),

    // Agent 链路
    AGENT_TIMEOUT(3001, "服务响应超时，请重试", HttpStatus.GATEWAY_TIMEOUT),
    AGENT_ERROR(3002, "Agent 服务异常", HttpStatus.BAD_GATEWAY),
    UPSTREAM_ERROR(3003, "上游服务异常", HttpStatus.BAD_GATEWAY),

    // 兜底
    INTERNAL_ERROR(5000, "服务器内部错误", HttpStatus.INTERNAL_SERVER_ERROR);

    private final int code;
    private final String defaultMessage;
    private final HttpStatus status;

    ErrorCode(int code, String defaultMessage, HttpStatus status) {
        this.code = code;
        this.defaultMessage = defaultMessage;
        this.status = status;
    }

    public int code() {
        return code;
    }

    public String defaultMessage() {
        return defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }
}
