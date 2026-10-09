package com.psych.dto;

/**
 * 故事集（心声墙）上的一条短文字。
 */
public record WallMessage(
        String id,        // 心声 ID
        String username,  // 发布者昵称
        String content,   // 一句话内容
        long likes,       // 点赞数
        boolean liked,    // 当前登录用户是否已点过赞
        boolean owner     // 是否为当前登录用户发布（控制删除入口的显示）
) {
}
