package com.psych.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 注册请求参数。注解即校验规则，不通过时由全局异常处理器统一返回错误信息。
 */
public record RegisterRequest(

        @NotBlank(message = "邮箱不能为空")
        @Email(message = "邮箱格式不正确")
        String email,

        @NotBlank(message = "用户ID不能为空")
        @Pattern(regexp = "^1[3-9]\\d{9}$", message = "请输入正确的手机号")
        String userId,

        @NotBlank(message = "密码不能为空")
        @Size(min = 6, message = "密码至少 6 位")
        String password
) {
}
