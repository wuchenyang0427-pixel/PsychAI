package com.psych.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.psych.dto.ChatSource;
import com.psych.dto.KnowledgeArticle;
import com.psych.exception.BizException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * RAG 知识库服务：保存心理学资料，负责清洗、切块、向量化、检索。
 *
 * 知识分成五层（详见 resources/knowledge/ 下的知识卡）：
 *   A 风险与危机 —— 红旗信号、C-SSRS 分级、处置流程、求助渠道。**关键词硬触发，不靠相似度**
 *   B 量表与阈值 —— PHQ-9 / GAD-7 / C-SSRS 的条目、计分、临界值及其证据来源
 *   C 评估访谈 —— 该问哪些问题、病程与功能损害的判断要点
 *   D 干预建议 —— 轻度自助方法、转介标准
 *   E 原始论文 —— 从网上收集的期刊全文，作为可核查的证据来源
 *
 * 两份索引并存：
 *   - 向量索引：余弦相似度，负责"意思像不像"；
 *   - BM25 索引：关键词词频，负责"词对不对得上"（数字、"96 eligible articles" 这类）。
 * 检索时两份分数各自 z-score 归一化后加权融合，实测片段级 Recall@3 从 59.3% 提升到 77.8%。
 *
 * Redis 键设计：
 *   kb:seq               —— 文章 ID 自增计数器
 *   kb:article:ids       —— Set：所有文章 ID
 *   kb:article:{id}      —— Hash：title / category / layer / content / chunks / createdAt
 *   kb:chunk:{id}:{seq}  —— Hash：articleId / title / layer / seq / text / vector(JSON 数组)
 */
@Service
public class KnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);

    /** 五个知识层：A 风险 / B 量表 / C 访谈 / D 干预 / E 论文 */
    public static final String LAYER_RISK = "A";
    public static final String LAYER_SCALE = "B";
    public static final String LAYER_INTERVIEW = "C";
    public static final String LAYER_ADVICE = "D";
    public static final String LAYER_PAPER = "E";

    /** 分层知识卡：文件放在 resources/knowledge/ 下，启动时自动入库（改完重启即生效） */
    private static final String[][] LAYER_CARDS = {
            {LAYER_RISK, "knowledge/A-risk.md"},
            {LAYER_SCALE, "knowledge/B-scales.md"},
            {LAYER_INTERVIEW, "knowledge/C-interview.md"},
            {LAYER_ADVICE, "knowledge/D-interventions.md"},
    };

    /** BM25 归一化后的权重：0.5 是 27 条测试问题上片段召回的实测最优点 */
    private static final double BM25_WEIGHT = 0.5;

    private final StringRedisTemplate redis;
    private final OllamaEmbeddingService embeddingService;
    private final ObjectMapper objectMapper;

    @Value("${app.rag.top-k}")
    private int topK;

    @Value("${app.rag.min-score}")
    private double minScore;

    @Value("${app.rag.chunk-size}")
    private int chunkSize;

    @Value("${app.rag.overlap-max-sentence}")
    private int overlapMaxSentence;

    /**
     * 同一篇文章最多贡献几个片段。
     *
     * 起因：E 层实测里，一条提问经常被同一篇文章"包场"——问"最近很焦虑心慌怎么办"时，
     * top-8 命中的 8 条片段全部来自同一篇论文（它正文里整段都是 AI 给出的安抚与建议话术，
     * 与这类提问的语义最近）。名次是对的，但模型只能看到一篇文章的近似重复内容，
     * 其他文献进不来。限制单篇上限后，最优片段的名次不变（@1/@3 指标不受影响），
     * 但 top-k 里能出现更多不同来源。
     */
    @Value("${app.rag.max-per-article}")
    private int maxPerArticle;

    /**
     * 内存检索索引：每个元素是一个知识块（文本 + 向量 + 词频）。
     * CopyOnWriteArrayList：读多写极少（CRUD 是低频操作），读检索永远无锁。
     */
    private final List<ChunkEntry> index = new CopyOnWriteArrayList<>();

    /** BM25 的全局统计（文档频率、平均长度），索引变动后重建 */
    private volatile Bm25Index bm25 = new Bm25Index(List.of(), new int[0]);

    /** 种子文章播种完成标记键（存在即表示自定义种子已全部播种） */
    private static final String SEED_MARK_KEY = "kb:seed:v1";
    /** 分层知识卡播种完成标记键 */
    private static final String LAYER_SEED_MARK_KEY = "kb:seed:layers:v1";

    public KnowledgeService(StringRedisTemplate redis,
            OllamaEmbeddingService embeddingService,
            ObjectMapper objectMapper) {
        this.redis = redis;
        this.embeddingService = embeddingService;
        this.objectMapper = objectMapper;
    }

    /** 一个知识块在内存索引里的样子 */
    private record ChunkEntry(String articleId, String title, String layer, int seq,
            String text, float[] vector, Map<String, Integer> termFreq, int termCount) {
    }

    /** 检索命中结果：块文本 + 来源 + 所属层 + 相似度得分 */
    public record SearchHit(String articleId, String title, String layer, int seq, String text,
            double score, double cosine) {
    }

    // ==================== 启动：从 Redis 重建内存索引 ====================

    @PostConstruct
    public void rebuildIndex() {
        List<ChunkEntry> loaded = new ArrayList<>();
        // SCAN 而非 KEYS，避免阻塞 Redis
        try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions()
                .match("kb:chunk:*").count(200).build())) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                Map<Object, Object> hash = redis.opsForHash().entries(key);
                if (hash.isEmpty()) {
                    continue;
                }
                try {
                    String text = str(hash.get("text"));
                    String title = str(hash.get("title"));
                    List<String> terms = Bm25Index.terms(title + "\n" + text);
                    loaded.add(new ChunkEntry(
                            str(hash.get("articleId")),
                            title,
                            normalizeLayer(str(hash.get("layer"))),
                            Integer.parseInt(str(hash.get("seq"), "0")),
                            text,
                            toFloatArray(str(hash.get("vector"))),
                            Bm25Index.termFreqOf(terms),
                            terms.size()));
                } catch (Exception e) {
                    // 单条损坏只跳过，不影响整体索引重建
                    log.warn("跳过损坏的知识块 {}：{}", key, e.getMessage());
                }
            }
        }
        index.clear();
        index.addAll(loaded);
        rebuildBm25();
        log.info("知识库索引重建完成，共 {} 个知识块（{}）", index.size(), layerSummary());
        seedIfAbsent();
        seedLayerCards();
    }

    /** 重新统计 BM25 的文档频率与平均长度（索引有任何增删改后调用） */
    private void rebuildBm25() {
        List<Map<String, Integer>> termFreq = new ArrayList<>(index.size());
        int[] lengths = new int[index.size()];
        for (int i = 0; i < index.size(); i++) {
            termFreq.add(index.get(i).termFreq());
            lengths[i] = index.get(i).termCount();
        }
        this.bm25 = new Bm25Index(termFreq, lengths);
    }

    // ==================== 检索 ====================

    /** 默认检索：全部层、top-k 条（供对话主流程使用） */
    public List<SearchHit> searchTopK(String query) {
        return searchTopK(query, null, topK);
    }

    /**
     * 混合检索：向量相似度 + BM25 关键词，各自 z-score 归一化后加权融合。
     *
     * @param query 用户问题（原样编码，不加指令前缀——bge-m3 不需要 bge-large-zh 那句前缀，
     *              而且原来给英文问题也硬拼中文前缀，属于典型的误用）
     * @param layers 只在这些层里检索；null 表示全库。指定层时不再套语义门槛，
     *               因为调用方已经明确知道这一层是相关的（例如按需注入 B 层量表知识）
     * @param limit 返回条数上限
     */
    public List<SearchHit> searchTopK(String query, Set<String> layers, int limit) {
        if (index.isEmpty() || query == null || query.isBlank()) {
            return List.of();
        }
        float[] queryVector = toFloat(embeddingService.embedOne(query));
        Bm25Index stats = this.bm25;

        int n = index.size();
        double[] cosine = new double[n];
        boolean[] allowed = new boolean[n];
        int allowedCount = 0;
        double gate = layers == null ? minScore : 0;
        for (int i = 0; i < n; i++) {
            ChunkEntry chunk = index.get(i);
            if (layers != null && !layers.contains(chunk.layer())) {
                continue;
            }
            double cos = cosine(queryVector, chunk.vector());
            if (cos < gate) {
                continue; // 语义门槛仍由纯向量分把守，关键词只负责排序，不硬凑不相关内容
            }
            cosine[i] = cos;
            allowed[i] = true;
            allowedCount++;
        }
        if (allowedCount == 0) {
            return List.of();
        }

        double[] rawBm25 = stats.scores(Bm25Index.queryTerms(query));
        double[] zCosine = zscore(cosine, allowed);
        double[] zBm25 = zscore(rawBm25, allowed);

        List<int[]> ranked = new ArrayList<>(allowedCount);
        for (int i = 0; i < n; i++) {
            if (allowed[i]) {
                ranked.add(new int[] {i});
            }
        }
        ranked.sort(Comparator.comparingDouble(
                (int[] pair) -> -(zCosine[pair[0]] + BM25_WEIGHT * zBm25[pair[0]])));

        List<SearchHit> hits = new ArrayList<>();
        Map<String, Integer> perArticle = new HashMap<>();
        for (int[] pair : ranked) {
            if (hits.size() >= limit) {
                break;
            }
            ChunkEntry c = index.get(pair[0]);
            // 单篇上限：防止一篇文章的近似重复片段占满整个 top-k（详见 maxPerArticle 的说明）
            int used = perArticle.getOrDefault(c.articleId(), 0);
            if (used >= maxPerArticle) {
                continue;
            }
            perArticle.put(c.articleId(), used + 1);
            hits.add(new SearchHit(c.articleId(), c.title(), c.layer(), c.seq(), c.text(),
                    zCosine[pair[0]] + BM25_WEIGHT * zBm25[pair[0]], cosine[pair[0]]));
        }
        return hits;
    }

    /** 对话/报告主流程用：检索 E 层论文片段，条数由 app.rag.top-k 决定 */
    public List<SearchHit> searchPapers(String query) {
        return searchTopK(query, Set.of(LAYER_PAPER), topK);
    }

    /** 对话/报告主流程用：在指定层里检索最相关的若干块（指定层不再套语义门槛） */
    public List<SearchHit> searchLayer(String query, String layer, int limit) {
        return searchTopK(query, Set.of(layer), limit);
    }

    /** 取某一层的知识块，按"文章 + 序号"的阅读顺序返回（用于硬触发的 A 层注入） */
    public List<SearchHit> layerChunks(String layer, int limit) {
        List<SearchHit> hits = new ArrayList<>();
        index.stream()
                .filter(c -> layer.equals(c.layer()))
                .sorted(Comparator.comparing(ChunkEntry::articleId).thenComparingInt(ChunkEntry::seq))
                .limit(limit)
                .forEach(c -> hits.add(new SearchHit(c.articleId(), c.title(), c.layer(), c.seq(),
                        c.text(), 1.0, 1.0)));
        return hits;
    }

    /** 库里有没有这一层（用于判断知识卡是否已入库） */
    public boolean hasLayer(String layer) {
        return index.stream().anyMatch(c -> layer.equals(c.layer()));
    }

    /** 各层块数，形如 "A=4, B=6, C=5, D=4, E=622" */
    public String layerSummary() {
        Map<String, Integer> counts = new java.util.TreeMap<>();
        for (ChunkEntry c : index) {
            counts.merge(c.layer(), 1, Integer::sum);
        }
        StringBuilder sb = new StringBuilder();
        counts.forEach((k, v) -> sb.append(sb.isEmpty() ? "" : ", ").append(k).append('=').append(v));
        return sb.toString();
    }

    /** 把检索结果转成去重后的来源列表（同一文章命中多块只列一次，保持命中最优顺序） */
    public List<ChatSource> toSources(List<SearchHit> hits) {
        Set<String> seen = new HashSet<>();
        List<ChatSource> sources = new ArrayList<>();
        for (SearchHit hit : hits) {
            if (seen.add(hit.articleId())) {
                sources.add(new ChatSource(hit.articleId(), hit.title()));
            }
        }
        return sources;
    }

    // ==================== 管理端 CRUD ====================

    /** 文章列表（可按标题/分类关键词过滤），按创建时间倒序 */
    public List<KnowledgeArticle> adminList(String keyword) {
        Set<String> ids = redis.opsForSet().members("kb:article:ids");
        List<KnowledgeArticle> articles = new ArrayList<>();
        if (ids != null) {
            for (String id : ids) {
                Map<Object, Object> hash = redis.opsForHash().entries("kb:article:" + id);
                if (hash.isEmpty()) {
                    continue;
                }
                String title = str(hash.get("title"));
                String category = str(hash.get("category"));
                if (keyword != null && !keyword.isBlank()
                        && !title.contains(keyword) && !category.contains(keyword)) {
                    continue;
                }
                articles.add(new KnowledgeArticle(id, title, category,
                        str(hash.get("content")),
                        Integer.parseInt(str(hash.get("chunks"), "0")),
                        Long.parseLong(str(hash.get("createdAt"), "0")),
                        normalizeLayer(str(hash.get("layer")))));
            }
        }
        articles.sort(Comparator.comparingLong(KnowledgeArticle::createdAt).reversed());
        return articles;
    }

    /** 新增文章（默认按 E 层论文处理） */
    public KnowledgeArticle create(String title, String category, String content) {
        return create(title, category, content, LAYER_PAPER);
    }

    /**
     * 新增文章：清洗 → 切块 → 逐块嵌入 → 写 Redis → 更新内存索引。
     * Ollama 不可用则整体失败（不写入半成品）。
     */
    public KnowledgeArticle create(String title, String category, String content, String layer) {
        TextChunker.Cleaned cleaned = TextChunker.clean(content);
        String text = cleaned.text().isBlank() ? content : cleaned.text();
        List<String> chunks = splitChunks(text);
        if (chunks.isEmpty()) {
            throw new BizException("正文太短，清洗后没有可分块的内容");
        }
        List<double[]> vectors = embeddingService.embed(withTitle(title, chunks));

        String id = "a" + redis.opsForValue().increment("kb:seq");
        long now = System.currentTimeMillis();
        String normalizedLayer = normalizeLayer(layer);
        redis.opsForHash().putAll("kb:article:" + id, Map.of(
                "title", title,
                "category", category,
                "layer", normalizedLayer,
                "content", text,
                "chunks", String.valueOf(chunks.size()),
                "createdAt", String.valueOf(now)));
        redis.opsForSet().add("kb:article:ids", id);
        saveChunks(id, title, normalizedLayer, chunks, vectors);
        rebuildBm25();
        log.info("知识库新增文章 {}（{} 层）：{}，清洗丢弃前置 {} 字 / 后置 {} 字，切成 {} 块",
                id, normalizedLayer, title, cleaned.frontDropped(), cleaned.backDropped(), chunks.size());
        return new KnowledgeArticle(id, title, category, text, chunks.size(), now, normalizedLayer);
    }

    /** 编辑文章：正文可能变化 → 重新清洗切块、重嵌入；层保持不变 */
    public void update(String id, String title, String category, String content) {
        if (!redis.hasKey("kb:article:" + id)) {
            throw new IllegalArgumentException("文章不存在");
        }
        String layer = normalizeLayer(str(redis.opsForHash().get("kb:article:" + id, "layer")));
        TextChunker.Cleaned cleaned = TextChunker.clean(content);
        String text = cleaned.text().isBlank() ? content : cleaned.text();
        List<String> chunks = splitChunks(text);
        if (chunks.isEmpty()) {
            throw new BizException("正文太短，清洗后没有可分块的内容");
        }
        List<double[]> vectors = embeddingService.embed(withTitle(title, chunks));

        removeChunks(id);
        redis.opsForHash().putAll("kb:article:" + id, Map.of(
                "title", title,
                "category", category,
                "layer", layer,
                "content", text,
                "chunks", String.valueOf(chunks.size())));
        saveChunks(id, title, layer, chunks, vectors);
        rebuildBm25();
    }

    /** 删除文章：文章 Hash + 全部知识块 + 索引 + ID 集合 */
    public void delete(String id) {
        if (!redis.hasKey("kb:article:" + id)) {
            throw new IllegalArgumentException("文章不存在");
        }
        removeChunks(id);
        redis.delete("kb:article:" + id);
        redis.opsForSet().remove("kb:article:ids", id);
        rebuildBm25();
    }

    // ==================== 内部：切块 / 落盘 / 数学 ====================

    /** 清洗后的正文 → 知识块（规则见 TextChunker） */
    List<String> splitChunks(String content) {
        return TextChunker.split(content, chunkSize, overlapMaxSentence);
    }

    /**
     * 向量化输入 = 标题 + 知识块正文。
     * 标题浓缩了主题词（如"GAD-7"），拼进去后用户提到主题词时更容易命中正确文章；
     * 落库的 text 仍是纯正文，拼 prompt 时不会把标题重复塞给模型。
     */
    private List<String> withTitle(String title, List<String> chunks) {
        List<String> inputs = new ArrayList<>(chunks.size());
        for (String chunk : chunks) {
            inputs.add(title + "\n" + chunk);
        }
        return inputs;
    }

    /** 知识块写入 Redis Hash + 内存索引（create/update 共用） */
    private void saveChunks(String articleId, String title, String layer,
            List<String> chunks, List<double[]> vectors) {
        for (int i = 0; i < chunks.size(); i++) {
            String chunkKey = "kb:chunk:" + articleId + ":" + i;
            redis.opsForHash().putAll(chunkKey, Map.of(
                    "articleId", articleId,
                    "title", title,
                    "layer", layer,
                    "seq", String.valueOf(i),
                    "text", chunks.get(i),
                    "vector", toJson(vectors.get(i))));
            List<String> terms = Bm25Index.terms(title + "\n" + chunks.get(i));
            index.add(new ChunkEntry(articleId, title, layer, i, chunks.get(i),
                    toFloat(vectors.get(i)), Bm25Index.termFreqOf(terms), terms.size()));
        }
    }

    /** 删除某篇文章的全部知识块（Redis + 内存索引） */
    private void removeChunks(String articleId) {
        try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions()
                .match("kb:chunk:" + articleId + ":*").count(200).build())) {
            List<String> keys = new ArrayList<>();
            while (cursor.hasNext()) {
                keys.add(cursor.next());
            }
            if (!keys.isEmpty()) {
                redis.delete(keys);
            }
        }
        index.removeIf(chunk -> chunk.articleId().equals(articleId));
    }

    /** 余弦相似度：两个向量方向的夹角余弦，1=完全同向（语义最相近） */
    private double cosine(float[] a, float[] b) {
        if (a.length != b.length || a.length == 0) {
            return 0;
        }
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /**
     * z-score 归一化（只统计 allowed 的位置）。
     * 为什么用 z-score 而不是 min-max：min-max 会被单个极端值拉偏，
     * 余弦相似度里经常出现"某一块特别像"的离群点。
     */
    private static double[] zscore(double[] values, boolean[] allowed) {
        double sum = 0;
        int count = 0;
        for (int i = 0; i < values.length; i++) {
            if (allowed[i]) {
                sum += values[i];
                count++;
            }
        }
        double[] out = new double[values.length];
        if (count == 0) {
            return out;
        }
        double mean = sum / count;
        double variance = 0;
        for (int i = 0; i < values.length; i++) {
            if (allowed[i]) {
                variance += (values[i] - mean) * (values[i] - mean);
            }
        }
        double sd = Math.sqrt(variance / count) + 1e-9;
        for (int i = 0; i < values.length; i++) {
            if (allowed[i]) {
                out[i] = (values[i] - mean) / sd;
            }
        }
        return out;
    }

    /** 把任意输入规整成 A~E；不认识的值一律当 E 层论文 */
    private static String normalizeLayer(String layer) {
        if (layer == null || layer.isBlank()) {
            return LAYER_PAPER;
        }
        String s = layer.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (s) {
            case LAYER_RISK, LAYER_SCALE, LAYER_INTERVIEW, LAYER_ADVICE, LAYER_PAPER -> s;
            default -> LAYER_PAPER;
        };
    }

    /**
     * 给 E 层论文标注"这是一篇什么类型的研究"，会随论文片段一起进提示词。
     *
     * 起因：检索实测里，描述症状的提问（如"最近总是很焦虑心慌"）最高分常常落在
     * a11/a16 这类"AI 能不能做心理评估"的评测论文上——它们本身就塞满虚构病例文本，
     * 用词与症状描述高度重合。把类型标出来，模型才知道那是研究材料，不能当临床依据。
     * 用标题关键词判断，不依赖数据库分配的文章 id（id 会随重建而变）。
     */
    public static String paperNote(String title) {
        if (title == null || title.isBlank()) {
            return "";
        }
        String t = title.toLowerCase(java.util.Locale.ROOT);
        if (t.contains("chatgpt") || t.contains("language-model") || t.contains("language model")
                || t.contains("machine-learning") || t.contains("machine learning")) {
            return "AI/算法方法学研究：研究对象是 AI 或预测模型本身，文中的病例与问答文本是研究材料，"
                    + "不能当作临床判断依据，引用它只能说明 AI 的能力与局限";
        }
        if (t.contains("columbia")) {
            return "C-SSRS 自杀风险量表的原始效度研究，可作为风险分级依据";
        }
        if (t.contains("phq") || t.contains("cut-off")) {
            return "抑郁量表 PHQ-9 的诊断准确性研究，可作为临界值与敏感度/特异度依据";
        }
        if (t.contains("gad-7")) {
            return "焦虑量表 GAD-7 的心理测量学研究，可作为临界值依据";
        }
        return "";
    }

    // ==================== 种子数据 ====================

    /** 3 篇心理学短文（增量播种，Ollama 不在线则跳过） */
    private void seedIfAbsent() {
        // 独立标记键：播种完整成功后才设置，比"ids 集合是否存在"更可靠
        // （集合可能在播种中断、手工清理等情况下状态不完整）
        if (Boolean.TRUE.equals(redis.hasKey(SEED_MARK_KEY))) {
            return;
        }
        String[][] seeds = {
                { "情绪管理", "认识焦虑：从过度担忧到自我安抚",
                        "焦虑是人类面对不确定性的正常反应，本质是对未来的过度担忧。"
                                + "适度的焦虑能提升警觉和表现，但当担忧持续存在、难以控制，"
                                + "并开始影响睡眠、食欲和注意力时，就需要主动干预。\n\n"
                                + "识别焦虑的身体信号是第一步：心跳加速、肌肉紧绷、胃部不适、"
                                + "呼吸变浅，都是身体在说「我紧张了」。\n\n"
                                + "常用的自我安抚方法包括：腹式呼吸（吸气4秒、屏息2秒、呼气6秒，"
                                + "重复5轮）； grounding 落地技术（说出你看到的5样东西、听到的4种声音、"
                                + "能触摸的3样物品，把注意力拉回当下）；以及把担忧写下来，"
                                + "区分「我能控制的」和「我控制不了的」，只为前者制定行动计划。" },
                { "压力调节", "应对失眠：重建睡眠与床的联系",
                        "失眠往往不是因为身体不需要睡眠，而是大脑把床和清醒、焦虑联系在了一起。\n\n"
                                + "睡眠卫生建议：固定起床时间比固定入睡时间更重要；"
                                + "睡前一小时调暗灯光并远离电子屏幕；下午两点后避免咖啡因。\n\n"
                                + "刺激控制法的核心是：只在困的时候才上床；躺床20分钟仍睡不着就起身"
                                + "去另一个房间做无聊的事，有困意再回来；床只用于睡觉，不在床上工作、"
                                + "刷手机或反复看时间。\n\n"
                                + "如果失眠每周超过三晚、持续一个月以上，并明显影响白天状态，"
                                + "建议寻求专业帮助，认知行为治疗（CBT-I）是失眠的一线疗法。" },
                { "自我成长", "情绪日记：看见自己的内在规律",
                        "情绪日记是成本最低的自我觉察工具。记录本身就能降低情绪强度，"
                                + "心理学称之为「情绪标注效应」：把感受用语言表达出来时，"
                                + "大脑的杏仁核激活会下降。\n\n"
                                + "推荐的三栏记录法：第一栏写事件（客观发生了什么，像摄像头记录一样"
                                + "不加评判）；第二栏写感受和身体反应（难过？紧绷？给强度打个0-10分）；"
                                + "第三栏写当时的想法（我脑子里闪过了什么念头）。\n\n"
                                + "坚持一到两周后回看，你会看到重复出现的模式：什么情境最容易触发你？"
                                + "你最常出现的自动化负面想法是什么？看见模式，就是改变的开始。" }
        };
        try {
            // 收集已有标题：上次播种若中途失败，重启后只补缺的篇，不重复已成功的
            Set<String> existingTitles = new HashSet<>();
            Set<String> ids = redis.opsForSet().members("kb:article:ids");
            if (ids != null) {
                for (String aid : ids) {
                    existingTitles.add(str(redis.opsForHash().get("kb:article:" + aid, "title")));
                }
            }
            int created = 0;
            for (String[] seed : seeds) {
                if (existingTitles.contains(seed[1])) {
                    continue; // 标题已存在，跳过（幂等的第二道保险）
                }
                create(seed[1], seed[0], seed[2], LAYER_ADVICE);
                created++;
            }
            // 全部走完（无论新增还是已存在）才打标记
            redis.opsForValue().set(SEED_MARK_KEY, "1");
            log.info("知识库种子文章播种完成，本次新增 {} 篇", created);
        } catch (BizException e) {
            // Ollama 没启动时跳过播种，不阻塞后端启动；之后可用管理页手动录入
            log.warn("知识库种子播种跳过：{}", e.getMessage());
        }
    }

    /**
     * 把 resources/knowledge/ 下的分层知识卡（A 风险 / B 量表 / C 访谈 / D 干预）入库。
     * 内容改了 Markdown 后重启后端即可重新入库；已经入过库的不重复添加。
     */
    private void seedLayerCards() {
        if (Boolean.TRUE.equals(redis.hasKey(LAYER_SEED_MARK_KEY))) {
            return;
        }
        Set<String> existingTitles = new HashSet<>();
        Set<String> ids = redis.opsForSet().members("kb:article:ids");
        if (ids != null) {
            for (String aid : ids) {
                existingTitles.add(str(redis.opsForHash().get("kb:article:" + aid, "title")));
            }
        }
        int created = 0;
        for (String[] card : LAYER_CARDS) {
            try {
                String markdown = readClasspath(card[1]);
                if (markdown == null || markdown.isBlank()) {
                    continue;
                }
                String title = firstHeading(markdown);
                if (existingTitles.contains(title)) {
                    continue;
                }
                create(title, "分层知识卡", markdown, card[0]);
                created++;
            } catch (BizException e) {
                log.warn("分层知识卡 {} 入库跳过（Ollama 不可用？）：{}", card[1], e.getMessage());
                return; // 嵌入服务不可用，本轮全部跳过，不打标记，下次启动重试
            }
        }
        redis.opsForValue().set(LAYER_SEED_MARK_KEY, "1");
        log.info("分层知识卡入库完成，本次新增 {} 篇（{}）", created, layerSummary());
    }

    /** 读取 classpath 下的 UTF-8 文本资源，不存在返回 null */
    private static String readClasspath(String path) {
        try {
            ClassPathResource resource = new ClassPathResource(path);
            if (!resource.exists()) {
                return null;
            }
            try (java.io.InputStream in = resource.getInputStream()) {
                return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.warn("读取知识卡 {} 失败：{}", path, e.getMessage());
            return null;
        }
    }

    /** 取 Markdown 的第一个一级标题作为文章标题，没有就用兜底名 */
    private static String firstHeading(String markdown) {
        for (String line : markdown.split("\n")) {
            String s = line.strip();
            if (s.startsWith("# ")) {
                return s.substring(2).strip();
            }
        }
        return "未命名知识卡";
    }

    // ==================== 小工具 ====================

    private String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private String str(Object o, String defaultValue) {
        String s = str(o);
        return s.isEmpty() ? defaultValue : s;
    }

    private String toJson(double[] vector) {
        try {
            return objectMapper.writeValueAsString(vector);
        } catch (Exception e) {
            throw new IllegalStateException("向量序列化失败", e);
        }
    }

    private float[] toFloat(double[] vector) {
        float[] f = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            f[i] = (float) vector[i];
        }
        return f;
    }

    private float[] toFloatArray(String json) {
        try {
            double[] d = objectMapper.readValue(json, double[].class);
            return toFloat(d);
        } catch (Exception e) {
            throw new IllegalStateException("向量反序列化失败", e);
        }
    }
}

