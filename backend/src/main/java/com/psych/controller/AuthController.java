package com.psych.controller;

import com.psych.dto.ApiResponse;
import com.psych.dto.LoginRequest;
import com.psych.dto.LoginResponse;
import com.psych.dto.RegisterRequest;
import com.psych.dto.UserInfo;
import com.psych.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证相关接口，统一前缀 /api/auth。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** 注册 */
    @PostMapping("/register")
    public ApiResponse<Void> register(@Valid @RequestBody RegisterRequest request) {
        authService.register(request);
        return ApiResponse.success(null);
    }

    /** 登录，成功返回 token 和角色 */
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.success(authService.login(request));
    }

    /** 登出（前端带上登录时拿到的 token） */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        authService.logout(authHeader);
        return ApiResponse.success(null);
    }

    /** 查询当前登录用户，前端可用来校验 token 是否仍有效 */
    @GetMapping("/me")
    public ApiResponse<UserInfo> me(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        return ApiResponse.success(authService.currentUser(authHeader));
    }
}
