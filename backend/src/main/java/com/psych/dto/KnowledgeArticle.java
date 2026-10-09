package com.psych.dto;

/**
 * 知识库中的一篇资料（分层知识卡或期刊论文，供 RAG 检索引用）。
 *
 * @param id        文章 ID，形如 "a1"（INCR kb:seq 生成）
 * @param title     标题（检索命中后作为"参考来源"展示给用户）
 * @param category  分类：分层知识卡 / 情绪管理 / 人际交往 / 压力调节 / 自我成长 …
 * @param content   正文全文（入库前已做过参考文献/作者单位清洗）
 * @param chunks    正文被切成的块数（只读展示，由切块逻辑算出）
 * @param createdAt 创建时间（毫秒时间戳）
 * @param layer     知识层：A 风险 / B 量表 / C 访谈 / D 干预 / E 论文
 */
public record KnowledgeArticle(String id, String title, String category,
        String content, int chunks, long createdAt, String layer) {
}
