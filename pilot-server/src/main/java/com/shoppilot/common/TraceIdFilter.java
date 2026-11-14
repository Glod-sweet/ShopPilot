package com.shoppilot.common;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * 为每个请求生成 traceId 并放进 exchange 属性，供日志与响应回传。
 *
 * 为什么需要它：api-contract.md 要求 Python 侧日志必须打印 traceId，
 * 而 traceId 的源头在前端（clientTraceId）或本过滤器。没有它就无法把
 * 「前端一次点击」和「Python 一次工具调用」串起来。
 *
 * 用 WebFilter 而非 OncePerRequestFilter：WebFlux 是响应式栈，
 * 没有 Servlet 那套过滤器链，用 WebFilter 才不阻塞事件循环。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter implements WebFilter {

    /** exchange 属性名，GlobalExceptionHandler 与各 Controller 都从这里取 */
    public static final String ATTR_TRACE_ID = "pilot.traceId";

    /** 前端可通过该请求头自带 traceId，便于跨端串联 */
    public static final String HEADER_TRACE_ID = "X-Trace-Id";

    /** 响应头回传，前端可在 Network 面板直接看到 */
    public static final String RESPONSE_HEADER = "X-Trace-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String traceId = exchange.getRequest().getHeaders().getFirst(HEADER_TRACE_ID);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }

        exchange.getAttributes().put(ATTR_TRACE_ID, traceId);
        exchange.getResponse().getHeaders().set(RESPONSE_HEADER, traceId);

        // 放进 Reactor Context，日志里可通过 MDC 桥接拿到（后续接 micrometer 时用得上）
        String finalTraceId = traceId;
        return chain.filter(exchange)
                .contextWrite(ctx -> ctx.put(ATTR_TRACE_ID, finalTraceId));
    }

    /** 供 Controller 内部取用 */
    public static String current(ServerWebExchange exchange) {
        Object v = exchange.getAttributes().get(ATTR_TRACE_ID);
        return v == null ? null : v.toString();
    }
}
