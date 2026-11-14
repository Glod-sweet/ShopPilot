package com.shoppilot.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 鉴权配置。
 * <p>
 * dev-mode 打开时允许前端用固定的 dev-token 直接以 dev-user-id 身份访问，
 * 目的是让前端在没有登录页的情况下也能联调；上线必须关掉。
 */
@ConfigurationProperties(prefix = "pilot.auth")
public record AuthProperties(
        String jwtSecret,
        long accessTokenTtlSeconds,
        String issuer,
        boolean devMode,
        String devToken,
        long devUserId
) {
}
