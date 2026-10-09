package com.psych.dto;

/**
 * 登录成功后返回给前端的数据。
 * token 之后访问受保护接口时放在 Authorization 请求头里。
 */
public record LoginResponse(
        String token,
        String userId,
        String role
) {
}
