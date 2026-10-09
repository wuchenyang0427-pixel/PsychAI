package com.psych.dto;

/**
 * 用户信息（对外）。故意不含密码，避免接口把密文泄露出去。
 */
public record UserInfo(
        String userId,
        String email,
        String role
) {
}
