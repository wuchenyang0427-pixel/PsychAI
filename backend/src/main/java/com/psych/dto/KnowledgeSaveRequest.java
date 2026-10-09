package com.psych.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 新增/编辑知识文章的请求体（管理端）。
 */
public record KnowledgeSaveRequest(
        @NotBlank(message = "标题不能为空") @Size(max = 100, message = "标题最长 100 字") String title,

        @NotBlank(message = "分类不能为空") String category,

        @NotBlank(message = "正文不能为空") @Size(max = 20000, message = "正文最长 20000 字") String content) {
}
