package com.psych.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 管理端新增/编辑心声的请求体。
 */
public record WallSaveRequest(

        @NotBlank(message = "用户名不能为空")
        @Size(max = 30, message = "用户名最长 30 个字")
        String username,

        @NotBlank(message = "内容不能为空")
        @Size(max = 200, message = "内容最长 200 个字")
        String content
) {
}
