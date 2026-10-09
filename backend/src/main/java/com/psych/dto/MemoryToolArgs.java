package com.psych.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 模型通过 capture_memory 工具返回的记忆提取结果。
 *
 * 红线：selfReported 只记用户亲口说过的内容，schema 描述里明确禁止
 * 写"患有XX症"等诊断结论——档案里只允许出现用户自己的话。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MemoryToolArgs(
        String summary,          // 这次会话聊了什么（1-3 句话摘要）
        Integer moodScore,       // 情绪分 1-10（1 最差，10 最好）
        List<String> keyEvents,  // 关键事件（用户提到的重要事情）
        List<String> selfReported, // 用户自述：原话要点，禁止诊断术语
        String riskLevel,        // 风险等级 low / medium / high
        ProfileUpdates profileUpdates, // 档案变更项（只填有变化的字段）
        List<String> openThreads // 未完结话题（AI 之后可以主动关心的事）
) {

    /**
     * 档案变更项：模型"返回了哪个字段就更新哪个字段"。
     * 注意：concerns/relations 是更新后的【完整列表】（整体替换，不是并集），
     * 这样"室友（关系紧张）"会被"室友（已和好）"替换掉而不是并存。
     * 字段为空字符串/空数组 = 本次没有变化，保持原样。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProfileUpdates(
            String nickname,         // 称呼
            String background,       // 身份背景（如：大二学生）
            List<String> concerns,   // 当前困扰（完整列表）
            List<String> relations,  // 重要人际关系（完整列表，如：室友（已和好））
            String style             // 沟通风格与性格特点
    ) {
    }
}
