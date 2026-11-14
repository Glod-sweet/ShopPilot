package com.shoppilot.security;

/**
 * 当前请求的登录身份。
 * 只放最小必要信息：id 与 username。权限判断需要更多信息时再回表查，
 * 不把整份用户数据塞进 token。
 */
public record UserContext(Long userId, String username) {
}
