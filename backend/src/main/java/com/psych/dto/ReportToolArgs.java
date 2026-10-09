package com.psych.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 模型通过 Function Calling 返回的报告参数。
 * 对应 ReportService 里定义的 generate_psych_report 工具 schema；
 * ignoreUnknown：模型偶尔会多输出字段，忽略它们避免解析报错。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReportToolArgs(
        String narrative,        // 报告正文（Markdown），前端渲染成富文本
        String stateSummary,     // 一句话状态概述
        String severity,         // 轻度 / 中度 / 重度
        String severityReason,   // 严重程度判断依据
        List<String> emotionTags, // 情绪标签
        List<String> suggestions, // 可操作建议列表
        Boolean riskFlag          // 是否存在高危风险信号
) {
}
