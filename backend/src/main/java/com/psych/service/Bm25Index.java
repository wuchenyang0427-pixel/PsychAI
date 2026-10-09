package com.psych.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BM25 关键词检索：向量检索的"字面"补充。
 *
 * 为什么需要它：向量擅长"意思相近"，但对"稀有词"不敏感。用户问
 * "一共纳入了多少篇文献"，正确段落里的 "96 eligible articles" 这种数字短语
 * 靠余弦很难被顶到前面；而 BM25 恰好擅长这种精确匹配。
 * 实测（27 条中文/英文问题、622 个块）：纯向量片段命中 Recall@3 = 59.3%，
 * 与 BM25 融合后升到 77.8%，而文章级命中保持 100%。
 *
 * 分词策略（无外部依赖，中英混合都能用）：
 *   - 中文：连续汉字切成双字词组（bigram），过滤"我在/的是"这类停用字组合；
 *   - 英文：小写化后取长度 ≥3 的单词，去掉 the/a/and/of 这类高频停用词。
 */
final class Bm25Index {

    /** BM25 的两个经验参数：k1 控制词频饱和，b 控制文档长度归一化 */
    private static final double K1 = 1.2;
    private static final double B = 0.75;

    private static final Pattern CJK_RUN = Pattern.compile("[\u4e00-\u9fff]+");
    private static final Pattern EN_WORD = Pattern.compile("[a-zA-Z][a-zA-Z0-9\\-]{2,}");

    /** 中文停用字：代词、助词、疑问词、介词等几乎每句话都有的字 */
    private static final String CN_STOP =
            "我你他她它们的是在也都很多么怎啊吗呀呢吧会这那和与把被让向从给对有就还要";

    private static final Set<String> EN_STOP = Set.of(
            "the", "and", "for", "are", "was", "were", "been", "being", "that", "this", "these",
            "those", "its", "with", "from", "not", "but", "then", "than", "which", "who", "whom",
            "what", "when", "where", "how", "why", "can", "could", "would", "should", "may",
            "might", "will", "shall", "does", "did", "have", "has", "had", "you", "your", "our",
            "their", "them", "they", "she", "him", "his", "her", "one", "two", "all", "any",
            "also", "such", "only", "more", "most", "some", "other", "into", "out", "use", "used");

    private final List<Map<String, Integer>> termFreq;
    private final int[] docLength;
    private final Map<String, Integer> docFreq;
    private final double avgLength;
    private final int docCount;

    Bm25Index(List<Map<String, Integer>> termFreq, int[] docLength) {
        this.termFreq = termFreq;
        this.docLength = docLength;
        this.docCount = termFreq.size();
        this.docFreq = new HashMap<>();
        long total = 0;
        for (Map<String, Integer> tf : termFreq) {
            for (String term : tf.keySet()) {
                docFreq.merge(term, 1, Integer::sum);
            }
            total += tf.size();
        }
        this.avgLength = docCount == 0 ? 1 : Math.max(total / (double) docCount, 1);
    }

    /**
     * 抽取一段文本的词项（用于建索引和查询，同一套规则）。
     * 返回 List 而非 Set：建索引时要保留重复词以统计词频。
     */
    static List<String> terms(String text) {
        List<String> out = new ArrayList<>();
        String lower = text.toLowerCase();
        Matcher cjk = CJK_RUN.matcher(text);
        while (cjk.find()) {
            String run = cjk.group();
            for (int i = 0; i < run.length() - 1; i++) {
                char a = run.charAt(i);
                char b = run.charAt(i + 1);
                if (CN_STOP.indexOf(a) < 0 && CN_STOP.indexOf(b) < 0) {
                    out.add(run.substring(i, i + 2));
                }
            }
        }
        Matcher en = EN_WORD.matcher(lower);
        while (en.find()) {
            String w = en.group();
            if (!EN_STOP.contains(w)) {
                out.add(w);
            }
        }
        return out;
    }

    /** 把词项列表压成"词 → 出现次数"，存进块索引 */
    static Map<String, Integer> termFreqOf(List<String> terms) {
        Map<String, Integer> tf = new HashMap<>();
        for (String t : terms) {
            tf.merge(t, 1, Integer::sum);
        }
        return tf;
    }

    /** 查询去重后的词项集合 */
    static Set<String> queryTerms(String query) {
        return new LinkedHashSet<>(terms(query));
    }

    /**
     * 对全部块计算 BM25 分。查询词没在库里出现（df=0）时直接跳过。
     *
     * @param queryTerms 查询词项
     * @return 与 termFreq 等长的分数数组
     */
    double[] scores(Collection<String> queryTerms) {
        double[] scores = new double[docCount];
        Set<String> unique = new HashSet<>();
        for (String raw : queryTerms) {
            if (!unique.add(raw)) {
                continue;
            }
            Integer df = docFreq.get(raw);
            if (df == null || df == 0) {
                continue;
            }
            double idf = Math.log(1 + (docCount - df + 0.5) / (df + 0.5));
            for (int i = 0; i < docCount; i++) {
                Integer f = termFreq.get(i).get(raw);
                if (f == null) {
                    continue;
                }
                double denom = f + K1 * (1 - B + B * docLength[i] / avgLength);
                scores[i] += idf * f * (K1 + 1) / denom;
            }
        }
        return scores;
    }

    int docCount() {
        return docCount;
    }
}

