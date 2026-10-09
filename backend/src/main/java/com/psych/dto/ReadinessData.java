package com.psych.dto;

import java.util.List;

/**
 * AI 对话信息完整度评估结果。
 * score 由模型给出（0-100），ready 由后端按阈值统一判定，保证规则确定。
 */
public record ReadinessData(
        int score,               // 信息完整度评分 0-100
        boolean ready,           // 是否达到生成报告的标准（score >= 阈值）
        String reason,           // 评分理由（一句话）
        List<String> missingInfo // 还缺少的关键信息项
) {
}
