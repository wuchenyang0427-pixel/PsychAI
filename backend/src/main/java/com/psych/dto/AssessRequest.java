package com.psych.dto;

import java.util.List;

/**
 * 信息完整度评分请求：只需对话历史，不需要单条消息。
 * （不能复用 ChatRequest，它的 message 带 @NotBlank 会让只传 history 的评分请求被误拦）
 */
public record AssessRequest(List<ChatMessage> history) {
}
