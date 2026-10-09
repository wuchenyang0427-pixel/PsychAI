package com.psych.dto;

import java.util.List;

/**
 * 一份完整的心理分析报告：模型生成的字段 + 系统补充的元数据。
 * 整份对象以 JSON 形式存入 Redis 键 report:{reportId}。
 */
public record ReportData(
        String reportId,          // 系统生成的报告唯一 ID
        String userId,            // 归属用户（用于"我的报告"隔离与越权校验）
        String createdAt,         // 生成时间
        String stateSummary,      // 一句话状态概述
        String severity,          // 轻度 / 中度 / 重度
        String severityReason,    // 严重程度判断依据
        List<String> emotionTags, // 情绪标签
        List<String> suggestions, // 可操作建议列表
        Boolean riskFlag,         // 是否存在高危风险信号
        String narrative          // 报告正文（Markdown）
) {
}
