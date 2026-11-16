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

    /**
     * 数据库不可用。
     * <p>
     * 为什么单独处理：数据库没起 / 连接池耗尽时，R2DBC 会抛
     * DataAccessResourceFailureException("Failed to obtain R2DBC Connection")。
     * 如果让它落到下面的 Exception 兜底分支，用户看到的是「服务器内部错误 5000」——
     * 既误导（像是代码 bug），又让前端无法判断「重试有没有用」。
     * <p>
     * 这里返回 503 + 明确文案：这是依赖不可用，稍后重试有意义。
     * 日志仍打完整堆栈，运维侧照样能定位。
     */
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataAccess(org.springframework.dao.DataAccessException ex,
                                                              ServerWebExchange exchange) {
        boolean connectionIssue = isConnectionIssue(ex);
        ErrorCode code = connectionIssue ? ErrorCode.DB_UNAVAILABLE : ErrorCode.INTERNAL_ERROR;

        log.error("数据访问异常 path={} 类型={} connectionIssue={}",
                exchange.getRequest().getPath(), ex.getClass().getSimpleName(), connectionIssue, ex);

        return ResponseEntity.status(code.status())
                .body(ApiResponse.fail(code, code.defaultMessage(), traceId(exchange)));
    }

    /**
     * 判断是否为「连接层面」的故障。
     * 遍历 cause 链：Spring 会把底层驱动异常包装多层，只看最外层会漏判。
     */
    private boolean isConnectionIssue(Throwable ex) {
        Throwable cur = ex;
        int depth = 0;
        while (cur != null && depth++ < 10) {
            String name = cur.getClass().getName();
            if (name.contains("DataAccessResourceFailure")
                    || name.contains("R2dbcNonTransientResourceException")
                    || name.contains("R2dbcTransientResourceException")
                    || cur instanceof java.net.ConnectException
                    || cur instanceof java.net.SocketTimeoutException) {
                return true;
            }
            String msg = cur.getMessage();
            if (msg != null && (msg.contains("Failed to obtain R2DBC Connection")
                    || msg.contains("Connection refused"))) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
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
