package com.psych.dto;

import java.util.List;

/**
 * 生成报告请求：把当前对话历史交给后端，由模型总结成结构化报告。
 */
public record GenerateReportRequest(List<ChatMessage> history) {
}
