package com.psych.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 用户端发布心声的请求体。
 * 昵称可不填（后端兜底为「匿名」），内容必填且最长 200 字。
 */
public record WallPublishRequest(

        @Size(max = 30, message = "昵称最长 30 个字")
        String username,

        @NotBlank(message = "内容不能为空")
        @Size(max = 200, message = "内容最长 200 个字")
        String content
) {
}
