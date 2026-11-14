package com.shoppilot.security;

import com.shoppilot.common.ErrorCode;
import com.shoppilot.config.PilotAgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 内部接口鉴权：Python Agent 反向调用 /internal/** 时必须带 X-Internal-Token。
 * <p>
 * 为什么单独一个过滤器：这类接口不受用户 JWT 约束（Python 不持有用户 token），
 * 但绝不能对外开放 —— 里面有加购、订单查询等敏感能力。
 * 生产环境还应叠加网络层限制（只允许 Agent 容器所在网段访问）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class InternalTokenFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(InternalTokenFilter.class);

    private static final String HEADER = "X-Internal-Token";

    private final PilotAgentProperties props;

    public InternalTokenFilter(PilotAgentProperties props) {
        this.props = props;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!path.startsWith("/internal/")) {
            return chain.filter(exchange);
        }

        String provided = exchange.getRequest().getHeaders().getFirst(HEADER);
        if (!matches(provided, props.internalToken())) {
            log.warn("内部接口鉴权失败 path={} token={}", path, provided == null ? "null" : "***");
            return reject(exchange);
        }
        return chain.filter(exchange);
    }

    /** 定长比较，避免按字符逐位比较带来的时序侧信道 */
    private boolean matches(String provided, String expected) {
        if (provided == null || expected == null) {
            return false;
        }
        return MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }

    private Mono<Void> reject(ServerWebExchange exchange) {
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = """
                {"code":%d,"message":"内部接口鉴权失败","data":null,"traceId":null}
                """.formatted(ErrorCode.UNAUTHORIZED.code());
        return response.writeWith(Mono.just(
                response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))));
    }
}
