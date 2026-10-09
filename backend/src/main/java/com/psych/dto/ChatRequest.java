package com.psych.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * AI 对话请求：本条消息 + 历史对话（可选）。
 */
public record ChatRequest(

        @NotBlank(message = "消息不能为空")
        String message,

        List<ChatMessage> history
) {
}
