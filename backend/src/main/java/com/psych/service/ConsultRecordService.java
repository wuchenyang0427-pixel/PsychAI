package com.psych.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.psych.dto.ConsultUserRecord;
import com.psych.dto.ReportData;
import com.psych.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 咨询记录服务：给后台「咨询记录」页提供"人员 + 心理调查报告"聚合数据。
 *
 * 数据口径（全部是 Redis 真实数据）：
 *   user:{userId}          —— 人员信息，只取 role=user 的被咨询对象
 *   user_reports:{userId}  —— 该用户的报告 ID 列表（LPUSH 写入，天然新报告在前）
 *   report:{reportId}      —— 报告本体
 *
 * 排序：最新报告为「重度」的人员置顶，其次中度、轻度，从未生成报告的在最后。
 */
@Service
public class ConsultRecordService {

    private static final Logger log = LoggerFactory.getLogger(ConsultRecordService.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final ReportService reportService;

    public ConsultRecordService(StringRedisTemplate redis, ObjectMapper mapper,
            ReportService reportService) {
        this.redis = redis;
        this.mapper = mapper;
        this.reportService = reportService;
    }

    /**
     * 列出全部普通用户及其最新报告摘要；keyword 非空时按人员ID模糊过滤。
     * 重度人员（最新报告）排在最前。
     */
    public List<ConsultUserRecord> listUsers(String keyword) {
        String kw = keyword == null ? "" : keyword.trim();

        List<ConsultUserRecord> records = new ArrayList<>();
        for (String userKey : scan("user:*")) {
            String json = redis.opsForValue().get(userKey);
            if (json == null) {
                continue;
            }
            User user;
            try {
                user = mapper.readValue(json, User.class);
            } catch (Exception e) {
                // 单条损坏数据跳过并留痕，不拖垮整页
                log.warn("用户 JSON 解析失败，已跳过: {}", e.getMessage());
                continue;
            }
            if (!"user".equals(user.getRole())) {
                continue; // 管理员不是被咨询对象
            }
            if (!kw.isEmpty() && !user.getUserId().contains(kw)) {
                continue; // 只按人员ID查询
            }
            records.add(buildRecord(user));
        }

        // 严重程度降序 → 高危信号优先 → 最新报告时间新者优先 → 人员ID兜底稳定排序
        records.sort(Comparator
                .comparingInt(ConsultUserRecord::severityRank)
                .reversed()
                .thenComparing(Comparator.comparing(ConsultUserRecord::latestRiskFlag).reversed())
                .thenComparing(ConsultUserRecord::latestCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(ConsultUserRecord::userId));
        return records;
    }

    /** 管理员视角查看某个人员的全部报告（新报告在前），复用 ReportService 的读逻辑保证口径一致 */
    public List<ReportData> reportsOfUser(String userId) {
        if (Boolean.FALSE.equals(redis.hasKey("user:" + userId))) {
            return List.of();
        }
        return reportService.listReports(userId);
    }

    // ==================== 内部方法 ====================

    /** 组装一个人员行：读报告索引列表，取第一条（最新）作为摘要 */
    private ConsultUserRecord buildRecord(User user) {
        String uid = user.getUserId();
        List<String> reportIds = redis.opsForList().range("user_reports:" + uid, 0, -1);
        long count = reportIds == null ? 0 : reportIds.size();

        String latestId = null;
        String latestCreatedAt = null;
        String latestSeverity = null;
        String latestSummary = null;
        boolean latestRisk = false;
        boolean severe = false;

        if (reportIds != null && !reportIds.isEmpty()) {
            ReportData latest = readReport(reportIds.get(0));
            if (latest != null) {
                latestId = latest.reportId();
                latestCreatedAt = latest.createdAt();
                latestSeverity = latest.severity();
                latestSummary = latest.stateSummary();
                latestRisk = Boolean.TRUE.equals(latest.riskFlag());
                severe = "重度".equals(latest.severity());
            }
        }

        return new ConsultUserRecord(
                uid,
                user.getEmail(),
                user.getCreatedAt(),
                count,
                latestId,
                latestCreatedAt,
                latestSeverity,
                latestSummary,
                latestRisk,
                severe);
    }

    /** 读单份报告 JSON，损坏或不存在返回 null（与 ReportService 的容错口径一致） */
    private ReportData readReport(String reportId) {
        try {
            String json = redis.opsForValue().get("report:" + reportId);
            return json == null ? null : mapper.readValue(json, ReportData.class);
        } catch (Exception e) {
            log.warn("报告 JSON 解析失败，已跳过 {}: {}", reportId, e.getMessage());
            return null;
        }
    }

    /** Redis SCAN：生产安全的键遍历，不用 KEYS */
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
}
