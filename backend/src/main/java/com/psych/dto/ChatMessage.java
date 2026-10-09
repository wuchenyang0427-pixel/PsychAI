package com.psych.dto;

/**
 * 一条对话消息。role 只会是 "user" 或 "assistant"。
 * 前端把历史对话转成这个结构传过来，模型才有上下文。
 */
public record ChatMessage(String role, String content) {
}
