package com.psych.dto;

import java.util.List;

/**
 * 用户累积档案：跨会话长期维护的"这个人是谁"。
 * 字段故意用英文，与模型工具 schema 的 profileUpdates 一一对应，便于合并。
 * 整份对象存 Redis 键 user_profile:{userId}，不设 TTL（长期数据）。
 */
public record UserProfile(
        String nickname,        // 称呼
        String background,      // 身份背景（如：大二学生）
        List<String> concerns,  // 当前困扰（整体替换的最新列表）
        List<String> relations, // 重要人际关系（如：室友（已和好））
        String style,           // 沟通风格与性格特点
        String updatedAt,       // 最近一次档案合并时间
        List<String> sourceCards // 档案信息来源的记忆卡 ID 列表
) {
}
