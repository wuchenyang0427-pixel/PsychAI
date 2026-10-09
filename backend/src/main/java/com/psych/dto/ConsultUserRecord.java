package com.psych.dto;

/**
 * 咨询记录页的一行：一个被咨询用户 + 其最新心理调查报告摘要。
 * 数据全部来自 Redis 真实聚合，无报告时 latestXxx 字段为空。
 */
public record ConsultUserRecord(
        String userId,             // 人员ID
        String email,              // 邮箱
        Long createdAt,            // 注册时间（毫秒时间戳）
        long reportCount,          // 累计报告数
        String latestReportId,     // 最新报告 ID（无报告为 null）
        String latestCreatedAt,    // 最新报告生成时间（无报告为 null）
        String latestSeverity,     // 最新报告严重程度：轻度 / 中度 / 重度（无报告为 null）
        String latestStateSummary, // 最新报告一句话状态概述
        boolean latestRiskFlag,    // 最新报告是否含高危风险信号
        boolean severe             // 是否重度（最新报告 severity=重度），前端据此标红置顶
) {

    /** 排序权重：重度 3 ＞ 中度 2 ＞ 轻度 1 ＞ 无报告 0 */
    public int severityRank() {
        if ("重度".equals(latestSeverity)) {
            return 3;
        }
        if ("中度".equals(latestSeverity)) {
            return 2;
        }
        if ("轻度".equals(latestSeverity)) {
            return 1;
        }
        return 0;
    }
}
