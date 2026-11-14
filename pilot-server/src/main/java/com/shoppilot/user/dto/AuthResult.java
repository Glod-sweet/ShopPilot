package com.shoppilot.user.dto;

/**
 * 登录/注册的返回。前端拿到 token 后放进 Authorization: Bearer <token>。
 * 同时返回用户基本信息，省掉一次 /api/auth/me 请求。
 */
public record AuthResult(
        String token,
        long expiresIn,
        UserInfo user
) {

    public record UserInfo(
            Long id,
            String username,
            String nickname,
            String phone,
            String avatarUrl
    ) {
    }
}
