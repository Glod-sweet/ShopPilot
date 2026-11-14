package com.shoppilot.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 只引入 spring-security-crypto 的 BCrypt，不引整个 Spring Security。
 * 理由：密码哈希是唯一真正需要的安全能力，其余（过滤器链、CSRF、会话）
 * 在纯 JWT + 无状态接口的项目里用不上，引进来只会增加排查成本。
 */
@Configuration
public class PasswordConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
