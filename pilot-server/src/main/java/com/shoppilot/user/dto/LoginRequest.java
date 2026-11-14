package com.shoppilot.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 登录请求。
 * 用 username 而非 phone 登录：种子数据里 phone 可为空，username 才是唯一键。
 */
public record LoginRequest(
        @NotBlank(message = "用户名不能为空")
        @Size(max = 64)
        String username,

        @NotBlank(message = "密码不能为空")
        @Size(min = 6, max = 64, message = "密码长度需在 6~64 之间")
        String password
) {
}
