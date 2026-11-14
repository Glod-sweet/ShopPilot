package com.shoppilot.user;

import com.shoppilot.common.ApiResponse;
import com.shoppilot.common.TraceIdFilter;
import com.shoppilot.security.AuthWebFilter;
import com.shoppilot.user.dto.AuthResult;
import com.shoppilot.user.dto.LoginRequest;
import com.shoppilot.user.dto.RegisterRequest;
import com.shoppilot.user.service.UserService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 登录 / 注册 / 当前用户。
 * 这三个接口是全站鉴权的入口：前端拿到 token 后所有请求都带 Authorization 头。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/login")
    public Mono<ApiResponse<AuthResult>> login(@Valid @RequestBody LoginRequest req,
                                               ServerWebExchange exchange) {
        return userService.login(req)
                .map(r -> ApiResponse.ok(r, TraceIdFilter.current(exchange)));
    }

    @PostMapping("/register")
    public Mono<ApiResponse<AuthResult>> register(@Valid @RequestBody RegisterRequest req,
                                                   ServerWebExchange exchange) {
        return userService.register(req)
                .map(r -> ApiResponse.ok(r, TraceIdFilter.current(exchange)));
    }

    @GetMapping("/me")
    public Mono<ApiResponse<AuthResult.UserInfo>> me(ServerWebExchange exchange) {
        var user = AuthWebFilter.currentUser(exchange);
        return userService.findById(user.userId())
                .map(info -> ApiResponse.ok(info, TraceIdFilter.current(exchange)));
    }
}
