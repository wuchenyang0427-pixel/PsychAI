package com.psych.dto;

import java.util.List;

/**
 * 数据分析看板的一整包真实统计数据（全部由 Redis 聚合得出，无任何模拟值）。
 */
public record StatsOverview(
        Kpi kpi,                  // 顶部四个核心指标
        Trend moodTrend,          // 平均情绪趋势（近 21 天，按日平均情绪分）
        Trend sessionTrend,       // 咨询会话统计（近 7 天，每日记忆卡数）
        Trend activityTrend,      // 用户活跃度（近 21 天，每日活跃用户数）
        Summary summary           // 核心指标摘要
) {
    /** 四个 KPI；avgMood 无数据时为 null（前端展示"—"，不造假数字） */
    public record Kpi(
            long userCount,     // 普通用户总数（不含管理员）
            long likeCount,     // 故事集累计点赞
            long sessionCount,  // 咨询会话数 = 记忆卡总数（一张卡 = 一次已收尾的会话）
            Double avgMood      // 全部记忆卡情绪分平均值，保留一位小数
    ) {
    }

    /** 一条趋势：横轴日期(MM-dd) + 纵轴数值；情绪趋势允许 null 表示当天无数据 */
    public record Trend(List<String> dates, List<? extends Number> values) {
    }

    public record Summary(
            long stories,           // 心声总数
            long newUsersThisWeek,  // 本周新增用户
            long reports,           // 已生成报告总数
            long highRisk           // 高危（riskLevel=high）记忆卡数量
    ) {
    }
}
