package com.psych.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.psych.dto.MemoryCard;
import com.psych.dto.StatsOverview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 数据分析看板统计服务。
 *
 * 所有数字的唯一来源都是 Redis 里的真实业务数据，口径如下：
 *   user:*            → 普通用户数、本周新增用户
 *   wall:msg:* 的 likes → 故事集累计点赞
 *   memory:card:*     → 会话数、平均情绪分、情绪趋势、活跃度（一张卡 = 一次已收尾的会话）
 *   report:*          → 已生成报告数
 *
 * 没有数据的日期输出 null（情绪趋势）或 0（计数类），绝不编造平滑数据。
 */
@Service
public class StatsService {

    private static final Logger log = LoggerFactory.getLogger(StatsService.class);
    private static final DateTimeFormatter LABEL_FMT = DateTimeFormatter.ofPattern("MM-dd");
    private static final int MOOD_DAYS = 21;
    private static final int SESSION_DAYS = 7;

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public StatsService(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    public StatsOverview overview() {
        // ---------- 一次性读出所有记忆卡（多个图表共用，避免重复扫描） ----------
        Set<String> cardKeys = scan("memory:card:*");
        List<MemoryCard> cards = new ArrayList<>();
        List<String> cardJsons = cardKeys.isEmpty()
                ? List.of()
                : redis.opsForValue().multiGet(cardKeys);
        if (cardJsons != null) {
            for (String json : cardJsons) {
                if (json == null) {
                    continue;
                }
                try {
                    cards.add(mapper.readValue(json, MemoryCard.class));
                } catch (Exception e) {
                    // 单条损坏不能拖垮整个看板，跳过并留痕
                    log.warn("记忆卡 JSON 解析失败，已跳过: {}", e.getMessage());
                }
            }
        }

        StatsOverview.Kpi kpi = buildKpi(cards);
        StatsOverview.Summary summary = buildSummary(cards);
        StatsOverview.Trend moodTrend = buildMoodTrend(cards);
        StatsOverview.Trend sessionTrend = buildSessionTrend(cards);
        StatsOverview.Trend activityTrend = buildActivityTrend(cards);

        return new StatsOverview(kpi, moodTrend, sessionTrend, activityTrend, summary);
    }

    // ==================== KPI ====================

    private StatsOverview.Kpi buildKpi(List<MemoryCard> cards) {
        long userCount = 0;
        Set<String> userKeys = scan("user:*");
        List<String> userJsons = userKeys.isEmpty() ? List.of() : redis.opsForValue().multiGet(userKeys);
        if (userJsons != null) {
            for (String json : userJsons) {
                if (json == null) {
                    continue;
                }
                try {
                    JsonNode node = mapper.readTree(json);
                    // KPI 口径：咨询者总数只算普通用户，管理员不是被服务对象
                    if ("user".equals(node.path("role").asText(""))) {
                        userCount++;
                    }
                } catch (Exception e) {
                    log.warn("用户 JSON 解析失败，已跳过: {}", e.getMessage());
                }
            }
        }

        // 故事集累计点赞 = 每条心声 likes 字段之和（真实点赞数，不是去重点赞人数）
        long likeCount = 0;
        for (String key : scan("wall:msg:*")) {
            Object likes = redis.opsForHash().get(key, "likes");
            if (likes != null) {
                likeCount += Long.parseLong(String.valueOf(likes));
            }
        }

        long sessionCount = cards.size();

        Double avgMood = null;
        double moodSum = 0;
        int moodN = 0;
        for (MemoryCard c : cards) {
            if (c.moodScore() != null) {
                moodSum += c.moodScore();
                moodN++;
            }
        }
        if (moodN > 0) {
            avgMood = Math.round(moodSum / moodN * 10) / 10.0; // 保留一位小数
        }

        return new StatsOverview.Kpi(userCount, likeCount, sessionCount, avgMood);
    }

    // ==================== 指标摘要 ====================

    private StatsOverview.Summary buildSummary(List<MemoryCard> cards) {
        long stories = orZero(redis.opsForSet().size("wall:ids"));
        long reports = scan("report:*").size();
        long highRisk = cards.stream().filter(c -> "high".equals(c.riskLevel())).count();

        // 本周新增用户：user JSON 里的 createdAt 毫秒时间戳落在近 7 天
        long newUsersThisWeek = 0;
        long cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000;
        Set<String> userKeys = scan("user:*");
        List<String> userJsons = userKeys.isEmpty() ? List.of() : redis.opsForValue().multiGet(userKeys);
        if (userJsons != null) {
            for (String json : userJsons) {
                if (json == null) {
                    continue;
                }
                try {
                    JsonNode node = mapper.readTree(json);
                    long created = node.path("createdAt").asLong(0);
                    if (created >= cutoff) {
                        newUsersThisWeek++;
                    }
                } catch (Exception e) {
                    log.warn("用户 JSON 解析失败，已跳过: {}", e.getMessage());
                }
            }
        }

        return new StatsOverview.Summary(stories, newUsersThisWeek, reports, highRisk);
    }

    // ==================== 三条趋势 ====================

    /** 近 21 天平均情绪分：当天多张卡取平均；当天无卡为 null（前端断点，不连线造假） */
    private StatsOverview.Trend buildMoodTrend(List<MemoryCard> cards) {
        Map<LocalDate, double[]> sumCount = new HashMap<>(); // [sum, count]
        for (MemoryCard c : cards) {
            LocalDate day = parseDay(c.createdAt());
            if (day == null || c.moodScore() == null) {
                continue;
            }
            double[] sc = sumCount.computeIfAbsent(day, k -> new double[2]);
            sc[0] += c.moodScore();
            sc[1]++;
        }
        List<String> dates = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = MOOD_DAYS - 1; i >= 0; i--) {
            LocalDate day = today.minusDays(i);
            dates.add(day.format(LABEL_FMT));
            double[] sc = sumCount.get(day);
            values.add(sc == null ? null : Math.round(sc[0] / sc[1] * 10) / 10.0);
        }
        return new StatsOverview.Trend(dates, values);
    }

    /** 近 7 天咨询会话数：每天产生的记忆卡数量 */
    private StatsOverview.Trend buildSessionTrend(List<MemoryCard> cards) {
        Map<LocalDate, Long> counts = new HashMap<>();
        for (MemoryCard c : cards) {
            LocalDate day = parseDay(c.createdAt());
            if (day != null) {
                counts.merge(day, 1L, Long::sum);
            }
        }
        List<String> dates = new ArrayList<>();
        List<Long> values = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = SESSION_DAYS - 1; i >= 0; i--) {
            LocalDate day = today.minusDays(i);
            dates.add(day.format(LABEL_FMT));
            values.add(counts.getOrDefault(day, 0L));
        }
        return new StatsOverview.Trend(dates, values);
    }

    /** 近 21 天活跃用户数：当天产生过记忆卡的去重用户数 */
    private StatsOverview.Trend buildActivityTrend(List<MemoryCard> cards) {
        Map<LocalDate, Set<String>> active = new HashMap<>();
        for (MemoryCard c : cards) {
            LocalDate day = parseDay(c.createdAt());
            if (day != null && c.userId() != null) {
                active.computeIfAbsent(day, k -> new HashSet<>()).add(c.userId());
            }
        }
        List<String> dates = new ArrayList<>();
        List<Long> values = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = MOOD_DAYS - 1; i >= 0; i--) {
            LocalDate day = today.minusDays(i);
            dates.add(day.format(LABEL_FMT));
            values.add((long) active.getOrDefault(day, Set.of()).size());
        }
        return new StatsOverview.Trend(dates, values);
    }

    // ==================== 工具 ====================

    /** 记忆卡 createdAt 格式 "yyyy-MM-dd HH:mm:ss"，取前 10 位得到日期 */
    private LocalDate parseDay(String createdAt) {
        if (createdAt == null || createdAt.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(createdAt.substring(0, 10));
        } catch (Exception e) {
            return null;
        }
    }

    /** Redis SCAN：生产安全的键遍历，不用 KEYS（数据量大时会阻塞） */
    private Set<String> scan(String pattern) {
        Set<String> keys = new HashSet<>();
        ScanOptions options = ScanOptions.scanOptions().match(pattern).count(200).build();
        redis.execute((RedisCallback<Void>) connection -> {
            try (Cursor<byte[]> cursor = connection.scan(options)) {
                while (cursor.hasNext()) {
                    keys.add(new String(cursor.next(), StandardCharsets.UTF_8));
                }
            }
            return null;
        });
        return keys;
    }

    private long orZero(Long size) {
        return size == null ? 0 : size;
    }
}
