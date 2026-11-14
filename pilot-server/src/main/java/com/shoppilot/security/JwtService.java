package com.shoppilot.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * JWT 签发与校验。
 * <p>
 * 约定：subject 存 userId，附带 username 与 issuer。
 * Python 侧不解析 JWT —— 用户身份由 Java 通过 X-User-Id 传入，这是三端契约的硬边界。
 */
@Component
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private final SecretKey key;
    private final AuthProperties props;

    public JwtService(AuthProperties props) {
        this.props = props;
        byte[] secret = props.jwtSecret() == null ? new byte[0]
                : props.jwtSecret().getBytes(StandardCharsets.UTF_8);
        // HS256 要求密钥至少 256 bit，配置太短时直接启动失败，避免线上才发现
        if (secret.length < 32) {
            throw new IllegalStateException(
                    "pilot.auth.jwt-secret 至少需要 32 字节（当前 " + secret.length + "），请检查配置");
        }
        this.key = Keys.hmacShaKeyFor(secret);
    }

    public String issue(Long userId, String username) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .issuer(props.issuer())
                .claim("username", username)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(props.accessTokenTtlSeconds())))
                .signWith(key)
                .compact();
    }

    /**
     * 校验并解析。返回 null 表示 token 无效（过期/签名不对/格式错），
     * 由调用方决定是 401 还是降级为匿名。
     */
    public UserContext parse(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(props.issuer())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            Long userId = Long.valueOf(claims.getSubject());
            String username = claims.get("username", String.class);
            return new UserContext(userId, username);
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("JWT 校验失败: {}", e.getMessage());
            return null;
        }
    }

    public long ttlSeconds() {
        return props.accessTokenTtlSeconds();
    }
}
