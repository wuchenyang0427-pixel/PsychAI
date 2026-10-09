package com.psych.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 风险信号扫描（A 层知识的"硬触发"开关）。
 *
 * 为什么不能只靠向量检索：实测中用户说"我不想活了"时，
 * 向量检索 top-3 命中了两条参考文献条目（"…wish to die…"）和一条被切断的英文片段，
 * 真正的自杀风险知识反而排在后面——因为相似度对"风险"这件事并不敏感，
 * 参考文献里全是同类词汇。所以风险识别必须走确定性的关键词规则，
 * 命中后无条件把 A 层知识塞进上下文，不参与相似度排序。
 *
 * 误报（把普通表达判成风险）是可接受的代价：多给模型一份风险处置资料，
 * 由模型结合语境决定是否真的启动危机干预；漏报才是不可接受的。
 */
@Service
public class CrisisDetector {

    /** 风险等级：NONE 无信号 / ELEVATED 需要评估 / HIGH 必须按危机流程处理 */
    public enum Level {
        NONE, ELEVATED, HIGH
    }

    public record Signal(Level level, List<String> matched) {
        public boolean triggered() {
            return level != Level.NONE;
        }
    }

    /** 明确的自伤/自杀/伤人信号 */
    private static final List<String> HIGH_WORDS = List.of(
            "自杀", "想死", "死了算了", "不如死", "不想活", "活不下去", "活够了", "一了百了",
            "结束生命", "结束自己", "了结自己", "轻生", "寻死", "去死",
            "割腕", "自残", "自伤", "伤害自己", "吞药", "跳楼", "上吊", "烧炭", "跳下去",
            "遗书", "交代后事", "最后一面",
            "伤害别人", "伤害他人", "杀了他", "杀了她", "报复社会",
            "suicide", "suicidal", "kill myself", "end my life", "end it all", "want to die",
            "better off dead", "self-harm", "self harm", "cut myself", "overdose", "hang myself",
            "no reason to live");

    /** 需要主动评估的信号（绝望感、无价值感、成为负担感、被动死亡愿望） */
    private static final List<String> ELEVATED_WORDS = List.of(
            "绝望", "没有希望", "看不到希望", "撑不下去", "熬不下去", "活着没意思",
            "没希望", "毫无希望", "希望都没有", "没有盼头", "看不到盼头",
            "累赘", "拖累", "我是负担", "没有我更好", "一觉不醒", "不想醒来", "想消失",
            "想解脱", "想过死", "行尸走肉", "看不到尽头", "哭不出来",
            "hopeless", "worthless", "a burden", "no way out", "can't take it anymore",
            "nothing matters anymore");

    /** 否定词：紧挨在风险词前面的"不/没/别/未/无"表示在否认或转述，不计为风险 */
    private static final String NEGATIONS = "不没别未无";

    /**
     * 整词否认短语。只靠"前面 1-2 个字里有没有否定词"不够：
     * "我没想过伤害自己""也没有伤害别人的想法"这类常见回答里，否定词离风险词有 3-4 个字，
     * 结果把明确否认判成了 HIGH（端到端实测踩到过：用户逐条否认风险，报告却带着风险标记）。
     * 这里按完整短语匹配，并要求短语与风险词之间只夹几个连接字（要/再/去…），
     * 一旦中间出现标点或转折词就认为跨了小句，不再算否认——避免把
     * "我不觉得我想死""他不会的，我想死"这类真话一起否掉。
     */
    private static final List<String> DENIAL_PHRASES = List.of(
            "没想过", "没有想过", "从来没想过", "从没想过", "没有过", "从未有过", "从未",
            "没打算", "没有打算", "没有这个想法", "没有这种想法", "没有这样的想法");

    /** 否认短语与风险词之间允许夹的连接字，超出这个范围就认为不是同一小句 */
    private static final String DENIAL_FILLER = "要再去真会敢想打";

    /** 扫描一段文本（通常是用户最近几条消息拼起来），返回风险等级与命中的词 */
    public Signal scan(String text) {
        if (text == null || text.isBlank()) {
            return new Signal(Level.NONE, List.of());
        }
        String lower = text.toLowerCase(Locale.ROOT);
        Set<String> hits = new LinkedHashSet<>();
        for (String w : HIGH_WORDS) {
            if (contains(lower, w)) {
                hits.add(w);
            }
        }
        if (!hits.isEmpty()) {
            return new Signal(Level.HIGH, new ArrayList<>(hits));
        }
        for (String w : ELEVATED_WORDS) {
            if (contains(lower, w)) {
                hits.add(w);
            }
        }
        return new Signal(hits.isEmpty() ? Level.NONE : Level.ELEVATED, new ArrayList<>(hits));
    }

    /** 大小写不敏感的子串匹配 + 前置否定判断：命中处前面两字内有否定词就跳过，继续往后找 */
    private static boolean contains(String lower, String word) {
        String needle = word.toLowerCase(Locale.ROOT);
        int from = 0;
        while (true) {
            int idx = lower.indexOf(needle, from);
            if (idx < 0) {
                return false;
            }
            if (!negated(lower, idx)) {
                return true;
            }
            from = idx + needle.length();
        }
    }

    /** 判断这次命中是否处于"否认/转述"语境（"我不想死""我没想过伤害自己"都不算风险） */
    private static boolean negated(String text, int index) {
        // 1) 紧邻的否定词："我不想死"——"不"就在风险词前面 1-2 个字
        for (int i = Math.max(0, index - 2); i < index; i++) {
            if (NEGATIONS.indexOf(text.charAt(i)) >= 0) {
                return true;
            }
        }
        // 2) 稍远的整词否认："我没想过伤害自己""也没有伤害别人的想法"
        for (String phrase : DENIAL_PHRASES) {
            for (int gap = 0; gap <= 3 && index - phrase.length() - gap >= 0; gap++) {
                int from = index - phrase.length() - gap;
                if (!text.startsWith(phrase, from)) {
                    continue;
                }
                boolean sameClause = true;
                for (int k = from + phrase.length(); k < index; k++) {
                    if (DENIAL_FILLER.indexOf(text.charAt(k)) < 0) {
                        sameClause = false;
                        break;
                    }
                }
                if (sameClause) {
                    return true;
                }
            }
        }
        return false;
    }
}
