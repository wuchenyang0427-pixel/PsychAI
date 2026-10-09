package com.psych.dto;

/**
 * 统一响应结构：{ code, message, data }。
 * 前端只需要判断 code 是否为 0（成功），不用关心 HTTP 状态码细节。
 */
public record ApiResponse<T>(int code, String message, T data) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(0, "success", data);
    }

    public static <T> ApiResponse<T> fail(String message) {
        return new ApiResponse<>(1, message, null);
    }
}
