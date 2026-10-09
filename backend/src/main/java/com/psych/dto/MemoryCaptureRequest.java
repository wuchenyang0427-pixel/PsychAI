package com.psych.dto;

import java.util.List;

/**
 * 手动触发记忆提取的请求体：把一段对话历史交给 MemoryService。
 */
public record MemoryCaptureRequest(List<ChatMessage> history) {
}
