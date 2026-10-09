package com.psych.dto;

import java.util.List;

/**
 * GET /api/memory 的响应：AI 记住的关于当前用户的一切。
 */
public record MemoryView(
        UserProfile profile,        // 累积档案（可能为 null = 还没有记忆）
        List<MemoryCard> recentCards, // 最近几张记忆卡（新的在前）
        List<String> openThreads    // 当前未完结话题
) {
}
