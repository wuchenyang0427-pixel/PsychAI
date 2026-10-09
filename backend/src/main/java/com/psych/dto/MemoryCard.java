package com.psych.dto;

import java.util.List;

/**
 * 一张记忆卡：一次报告生成时对当次对话的记忆快照，长期存 Redis。
 * 结构 = MemoryToolArgs 的内容 + 系统元数据（cardId/userId/createdAt）
 * + 差集计算出的 resolvedThreads（这次已解决的上次话题）。
 */
public record MemoryCard(
        String cardId,               // 记忆卡唯一 ID
        String userId,               // 归属用户（只来自 token）
        String createdAt,            // 生成时间
        String summary,              // 会话摘要
        Integer moodScore,           // 情绪分 1-10
        List<String> keyEvents,      // 关键事件
        List<String> selfReported,   // 用户自述（原话要点，无诊断）
        String riskLevel,            // low / medium / high
        List<String> openThreads,    // 仍待跟进的未完结话题
        List<String> resolvedThreads // 这次已解决的上次话题（差集归档）
) {
}
