package com.shoppilot.security;

import com.shoppilot.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 鉴权过滤器。
 * <p>
 * 设计取舍：不引入 Spring Security 整套过滤器链。本项目只有「登录 / 未登录」两种状态，
 * 用不到它的 CSRF、Session、权限表达式；一个 30 行的 WebFilter 更透明，也不会
 * 和 WebFlux 的响应式线程模型打架。
 * <p>
 * 校验通过后把 {@link UserContext} 放进 exchange 属性，Controller 通过
 * {@link #currentUser(ServerWebExchange)} 取用。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuthWebFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(AuthWebFilter.class);

    public static final String ATTR_USER = "pilot.user";

    /** 白名单：不需要登录即可访问 */
    private static final List<String> WHITELIST = List.of(
            "/api/auth/login",
            "/api/auth/register",
            "/api/health"
    );

    private final JwtService jwtService;
    private final AuthProperties props;

    public AuthWebFilter(JwtService jwtService, AuthProperties props) {
        this.jwtService = jwtService;
        this.props = props;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        // 内部接口由 InternalTokenFilter 单独把关，不走用户鉴权
        if (path.startsWith("/internal/") || isWhitelisted(path)) {
            return chain.filter(exchange);
        }

        String token = resolveToken(exchange);

        // 开发模式：前端用固定 dev-token 免登录联调
        if (props.devMode() && props.devToken() != null && props.devToken().equals(token)) {
            exchange.getAttributes().put(ATTR_USER, new UserContext(props.devUserId(), "dev"));
            return chain.filter(exchange);
        }

        UserContext user = jwtService.parse(token);
        if (user == null) {
            return unauthorized(exchange, "未登录或登录已失效");
        }
        exchange.getAttributes().put(ATTR_USER, user);
        return chain.filter(exchange);
    }

    private boolean isWhitelisted(String path) {
        return WHITELIST.stream().anyMatch(path::equals);
    }

    private String resolveToken(ServerWebExchange exchange) {
        String header = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7).trim();
        }
        return null;
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = """
                {"code":%d,"message":"%s","data":null,"traceId":null}
                """.formatted(ErrorCode.UNAUTHORIZED.code(), message);
        var buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    /** Controller 取当前用户；理论上不会为 null（过滤器已拦截），兜底抛错避免 NPE */
    public static UserContext currentUser(ServerWebExchange exchange) {
        Object v = exchange.getAttributes().get(ATTR_USER);
        if (v instanceof UserContext uc) {
            return uc;
        }
        throw new IllegalStateException("当前请求未通过鉴权");
    }
}
