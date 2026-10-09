package com.psych.dto;

/**
 * AI 回答引用到的知识文章来源（前端在气泡下方展示"参考来源：《xxx》"）。
 *
 * @param articleId 文章 ID
 * @param title     文章标题
 */
public record ChatSource(String articleId, String title) {
}
