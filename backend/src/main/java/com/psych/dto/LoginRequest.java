package com.psych.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 登录请求参数。
 */
public record LoginRequest(

        @NotBlank(message = "用户ID不能为空")
        String userId,

        @NotBlank(message = "密码不能为空")
        String password
) {
}
