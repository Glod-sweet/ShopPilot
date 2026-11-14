package com.shoppilot.user.service;

import com.shoppilot.common.BizException;
import com.shoppilot.common.ErrorCode;
import com.shoppilot.security.JwtService;
import com.shoppilot.user.dto.AuthResult;
import com.shoppilot.user.dto.LoginRequest;
import com.shoppilot.user.dto.RegisterRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用户与鉴权。
 * <p>
 * 职责边界：本项目不做「用户中心」，注册登录只是为了给会话/购物车/订单
 * 提供一个真实的 userId，让 Agent 的「再来一单」有数据可查。
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final DatabaseClient db;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public UserService(DatabaseClient db, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.db = db;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public Mono<AuthResult> login(LoginRequest req) {
        return findByUsername(req.username())
                .switchIfEmpty(Mono.error(new BizException(ErrorCode.UNAUTHORIZED, "用户名或密码错误")))
                .flatMap(user -> {
                    String hash = (String) user.get("password_hash");
                    if (hash == null || hash.isBlank()
                            || !passwordEncoder.matches(req.password(), hash)) {
                        // 统一话术，不区分「用户不存在」和「密码错」，避免账号枚举
                        return Mono.error(new BizException(ErrorCode.UNAUTHORIZED, "用户名或密码错误"));
                    }
                    int status = toInt(user.get("status"), 1);
                    if (status != 1) {
                        return Mono.error(new BizException(ErrorCode.FORBIDDEN, "账号已被禁用"));
                    }
                    return Mono.just(buildResult(user));
                })
                .doOnSuccess(r -> log.info("登录成功 userId={}", r.user().id()));
    }

    public Mono<AuthResult> register(RegisterRequest req) {
        return findByUsername(req.username())
                .flatMap(existing -> Mono.<AuthResult>error(
                        new BizException(ErrorCode.CONFLICT, "用户名已被占用")))
                .switchIfEmpty(Mono.defer(() -> insertUser(req)))
                .cast(AuthResult.class);
    }

    private Mono<AuthResult> insertUser(RegisterRequest req) {
        String hash = passwordEncoder.encode(req.password());
        String nickname = req.nickname() == null || req.nickname().isBlank()
                ? req.username() : req.nickname();

        return db.sql("""
                        INSERT INTO sp_user (username, password_hash, nickname, phone, status)
                        VALUES (:username, :hash, :nickname, :phone, 1)
                        """)
                .bind("username", req.username())
                .bind("hash", hash)
                .bind("nickname", nickname)
                .bind("phone", req.phone() == null ? "" : req.phone())
                .fetch().rowsUpdated()
                .then(db.sql("""
                                SELECT id, username, nickname, phone, avatar_url, status
                                FROM sp_user WHERE username = :username
                                """)
                        .bind("username", req.username())
                        .map((row, meta) -> rowToMap(row, meta))
                        .one())
                .map(this::buildResult)
                .doOnSuccess(r -> log.info("注册成功 userId={} username={}",
                        r.user().id(), r.user().username()));
    }

    /** 当前登录用户信息，供前端刷新页面后恢复状态 */
    public Mono<AuthResult.UserInfo> findById(long userId) {
        return db.sql("""
                        SELECT id, username, nickname, phone, avatar_url
                        FROM sp_user WHERE id = :id
                        """)
                .bind("id", userId)
                .map((row, meta) -> {
                    Map<String, Object> m = rowToMap(row, meta);
                    return new AuthResult.UserInfo(
                            toLong(m.get("id")),
                            str(m.get("username")),
                            str(m.get("nickname")),
                            str(m.get("phone")),
                            str(m.get("avatar_url")));
                })
                .one()
                .switchIfEmpty(Mono.error(BizException.notFound("用户")));
    }

    private Mono<Map<String, Object>> findByUsername(String username) {
        return db.sql("""
                        SELECT id, username, password_hash, nickname, phone, avatar_url, status
                        FROM sp_user WHERE username = :username
                        """)
                .bind("username", username)
                .map((row, meta) -> rowToMap(row, meta))
                .one();
    }

    private AuthResult buildResult(Map<String, Object> user) {
        Long id = toLong(user.get("id"));
        String username = str(user.get("username"));
        String token = jwtService.issue(id, username);
        return new AuthResult(token, jwtService.ttlSeconds(), new AuthResult.UserInfo(
                id, username, str(user.get("nickname")), str(user.get("phone")), str(user.get("avatar_url"))));
    }

    private static Map<String, Object> rowToMap(io.r2dbc.spi.Row row, io.r2dbc.spi.RowMetadata meta) {
        Map<String, Object> m = new LinkedHashMap<>();
        meta.getColumnMetadatas().forEach(col -> m.put(col.getName(), row.get(col.getName())));
        return m;
    }

    private static Long toLong(Object o) {
        return com.shoppilot.common.RowValues.asLong(o);
    }

    private static int toInt(Object o, int dft) {
        return com.shoppilot.common.RowValues.asInt(o, dft);
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
