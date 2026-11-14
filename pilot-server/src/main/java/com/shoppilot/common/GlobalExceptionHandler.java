package com.shoppilot.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import java.util.stream.Collectors;

/**
 * 全局异常出口。所有错误都以 ApiResponse 结构返回，前端只需判断 code。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBiz(BizException ex, ServerWebExchange exchange) {
        log.warn("业务异常 code={} msg={}", ex.errorCode().code(), ex.getMessage());
        return ResponseEntity.status(ex.errorCode().status())
                .body(ApiResponse.fail(ex.errorCode(), ex.getMessage(), traceId(exchange)));
    }

    /** @Valid 校验失败：把第一个字段错误直接给前端，便于定位 */
    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(WebExchangeBindException ex,
                                                              ServerWebExchange exchange) {
        String message = ex.getFieldErrors().stream()
                .map(e -> e.getField() + " " + e.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(ErrorCode.PARAM_INVALID, message, traceId(exchange)));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> handleStatus(ResponseStatusException ex,
                                                          ServerWebExchange exchange) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        ErrorCode code = status == HttpStatus.UNAUTHORIZED ? ErrorCode.UNAUTHORIZED
                : status == HttpStatus.FORBIDDEN ? ErrorCode.FORBIDDEN
                : status == HttpStatus.NOT_FOUND ? ErrorCode.NOT_FOUND
                : ErrorCode.INTERNAL_ERROR;
        return ResponseEntity.status(ex.getStatusCode())
                .body(ApiResponse.fail(code, ex.getReason() == null ? code.defaultMessage() : ex.getReason(),
                        traceId(exchange)));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException ex,
                                                                    ServerWebExchange exchange) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(ErrorCode.PARAM_INVALID, ex.getMessage(), traceId(exchange)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnknown(Exception ex, ServerWebExchange exchange) {
        log.error("未捕获异常 path={}", exchange.getRequest().getPath(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultMessage(),
                        traceId(exchange)));
    }

    private String traceId(ServerWebExchange exchange) {
        Object v = exchange.getAttributes().get(TraceIdFilter.ATTR_TRACE_ID);
        return v == null ? null : v.toString();
    }
}
