package com.psych.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.psych.dto.ChatMessage;
import com.psych.dto.MemoryCard;
import com.psych.dto.MemoryToolArgs;
import com.psych.dto.MemoryToolArgs.ProfileUpdates;
import com.psych.dto.UserProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * AI 记忆服务：累积档案（user_profile）+ 记忆卡（memory:card）+ 未完结话题（memory:threads）。
 *
 * 仍然沿用零框架套路：HttpClient + Function Calling 强制结构化输出 + Redis 存储。
 *
 * 红线（写在这里防止后人不遵守）：
 * 1. 只记用户亲口说过的话（selfReported），绝不记 AI 的推断和诊断结论；
 * 2. riskLevel=high 时必须打 WARN 日志，后续可接预警动作；
 * 3. 所有 Redis 键都以 userId 结尾，userId 只能来自 token，绝不能来自 URL 参数；
 * 4. 记忆和档案不设 TTL，都是长期数据。
 */
@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    @Value("${app.deepseek.base-url}")
    private String baseUrl;

    @Value("${app.deepseek.api-key}")
    private String apiKey;

    @Value("${app.deepseek.model}")
    private String model;

    public MemoryService(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    // ===== Redis 键约定（全部以 userId 结尾，红线 3）=====
    static final String KEY_PROFILE = "user_profile:";   // + userId → 档案 JSON
    static final String KEY_CARD = "memory:card:";       // + cardId → 记忆卡 JSON
    static final String KEY_INDEX = "user_memories:";    // + userId → cardId 列表
    static final String KEY_THREADS = "memory:threads:"; // + userId → 未完结话题 JSON 数组

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * capture_memory 工具 schema：
     * - strict: true + additionalProperties: false：输出严格符合 schema；
     * - 所有字段都必填（strict 模式要求），"没有变化"时输出空串/空数组；
     * - profileUpdates 里的列表字段要求输出【更新后的完整列表】，
     *   这样"室友（关系紧张）"才会被"室友（已和好）"整体替换而不是并存。
     */
    private static final String CAPTURE_TOOLS = """
            [ {
              "type": "function",
              "function": {
                "name": "capture_memory",
                "description": "从咨询对话中提取长期记忆：会话摘要、情绪分、关键事件、用户自述、风险等级、档案更新项、未完结话题",
                "parameters": {
                  "type": "object",
                  "properties": {
                    "summary": {
                      "type": "string",
                      "description": "这次会话聊了什么，1-3 句话摘要"
                    },
                    "moodScore": {
                      "type": "integer",
                      "description": "用户本次会话的情绪分，1-10，1 最差 10 最好；无明确情绪时给 5"
                    },
                    "keyEvents": {
                      "type": "array",
                      "items": { "type": "string" },
                      "description": "用户提到的关键事件，没有则输出空数组"
                    },
                    "selfReported": {
                      "type": "array",
                      "items": { "type": "string" },
                      "description": "用户自述的原话要点。红线：只记录用户亲口说的内容，禁止使用诊断术语（如'患有抑郁症'），禁止写入 AI 的推断和结论；没有则输出空数组"
                    },
                    "riskLevel": {
                      "type": "string",
                      "enum": ["low", "medium", "high"],
                      "description": "本次对话的风险等级：出现自伤/自杀/伤人念头为 high，明显痛苦但无危险念头为 medium，一般倾诉为 low"
                    },
                    "profileUpdates": {
                      "type": "object",
                      "properties": {
                        "nickname": {
                          "type": "string",
                          "description": "对用户的合适称呼；本次对话没有新信息则输出空字符串"
                        },
                        "background": {
                          "type": "string",
                          "description": "身份背景（如：大二学生、程序员）；没有新信息则输出空字符串"
                        },
                        "concerns": {
                          "type": "array",
                          "items": { "type": "string" },
                          "description": "当前困扰的【更新后完整列表】（整体替换）；本次对话没有涉及困扰的变化则输出空数组"
                        },
                        "relations": {
                          "type": "array",
                          "items": { "type": "string" },
                          "description": "重要人际关系的【更新后完整列表】，每项格式如'室友（已和好）'（整体替换，状态变化要更新而不是新增一条）；本次对话没有涉及关系变化则输出空数组"
                        },
                        "style": {
                          "type": "string",
                          "description": "用户的沟通风格与性格特点；没有新信息则输出空字符串"
                        }
                      },
                      "required": ["nickname", "background", "concerns", "relations", "style"],
                      "additionalProperties": false
                    },
                    "openThreads": {
                      "type": "array",
                      "items": { "type": "string" },
                      "description": "对话结束仍未了结、值得下次主动关心的话题（如：打算这周找室友谈谈）。延续旧话题时保持原有措辞，只输出本次对话结束后仍然开放的话题；没有则输出空数组"
                    }
                  },
                  "required": ["summary", "moodScore", "keyEvents", "selfReported", "riskLevel", "profileUpdates", "openThreads"],
                  "additionalProperties": false
                },
                "strict": true
              }
            } ]
            """;

    /**
     * 从一段对话历史提取记忆。整个方法绝不抛异常（catch-all 只打日志）：
     * 报告已经生成成功了，不能被记忆提取拖垮报告接口。
     *
     * @return 生成的记忆卡；失败返回 null
     */
    public MemoryCard capture(String userId, List<ChatMessage> history) {
        try {
            if (history == null || history.isEmpty()) {
                return null;
            }
            UserProfile profile = readProfile(userId);
            List<String> prevThreads = readOpenThreads(userId);

            // 组装 messages：对话历史 + 记忆提取指令（带上当前档案和旧话题供参考）
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", model);
            // deepseek-flash 是思考模型：思考模式不支持强制 tool_choice，必须关闭思考
            body.putObject("thinking").put("type", "disabled");

            ArrayNode messages = body.putArray("messages");
            for (ChatMessage m : history) {
                ObjectNode node = messages.addObject();
                node.put("role", m.role());
                node.put("content", m.content());
            }
            ObjectNode instr = messages.addObject();
            instr.put("role", "user");
            instr.put("content", "请基于以上对话，立即调用 capture_memory 工具提取长期记忆。\n"
                    + "当前用户档案：" + (profile == null ? "（空，新用户）" : toJson(profile)) + "\n"
                    + "当前未完结话题：" + (prevThreads.isEmpty() ? "（无）" : prevThreads) + "\n"
                    + "要求：1) selfReported 只记用户亲口说的内容，禁止诊断术语；"
                    + "2) profileUpdates 输出更新后的完整列表，状态变化要替换旧条目；"
                    + "3) openThreads 只保留对话结束后仍开放的话题，延续旧话题保持原措辞。");

            body.set("tools", objectMapper.readTree(CAPTURE_TOOLS));
            ObjectNode toolChoice = body.putObject("tool_choice");
            toolChoice.put("type", "function");
            toolChoice.putObject("function").put("name", "capture_memory");

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .timeout(Duration.ofSeconds(90))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("记忆提取失败：模型返回 HTTP {}，user={}", response.statusCode(), userId);
                return null;
            }

            JsonNode toolCalls = objectMapper.readTree(response.body())
                    .path("choices").path(0).path("message").path("tool_calls");
            if (!toolCalls.isArray() || toolCalls.isEmpty()) {
                log.warn("记忆提取失败：模型未返回工具调用，user={}", userId);
                return null;
            }
            String args = toolCalls.get(0).path("function").path("arguments").asText();
            MemoryToolArgs tool = objectMapper.readValue(args, MemoryToolArgs.class);

            // 差集逻辑：上一版 openThreads 有、这一版没有的 = 已解决
            List<String> newThreads = tool.openThreads() == null
                    ? List.of() : tool.openThreads();
            List<String> resolved = new ArrayList<>(prevThreads);
            resolved.removeAll(newThreads);

            MemoryCard card = new MemoryCard(
                    UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                    userId,
                    LocalDateTime.now().format(FMT),
                    tool.summary() == null ? "" : tool.summary(),
                    tool.moodScore() == null ? 5 : Math.max(1, Math.min(10, tool.moodScore())),
                    tool.keyEvents() == null ? List.of() : tool.keyEvents(),
                    tool.selfReported() == null ? List.of() : tool.selfReported(),
                    tool.riskLevel() == null ? "low" : tool.riskLevel(),
                    newThreads,
                    resolved);

            // 存记忆卡本体 + 索引（都不设 TTL，红线 4）
            redis.opsForValue().set(KEY_CARD + card.cardId(),
                    objectMapper.writeValueAsString(card));
            redis.opsForList().leftPush(KEY_INDEX + userId, card.cardId());
            // 刷新未完结话题
            redis.opsForValue().set(KEY_THREADS + userId,
                    objectMapper.writeValueAsString(newThreads));
            // 合并档案
            mergeProfile(userId, profile, tool.profileUpdates(), card.cardId());

            // 红线 2：高风险必须留痕（目前先打日志，后续可接预警通知）
            if ("high".equalsIgnoreCase(card.riskLevel())) {
                log.warn("[HIGH RISK] 用户 {} 的对话出现高危风险信号，摘要：{}", userId, card.summary());
            }
            log.info("记忆卡已生成 user={} card={} 情绪分={} 风险={}，解决话题 {} 条",
                    userId, card.cardId(), card.moodScore(), card.riskLevel(), resolved.size());
            return card;
        } catch (Exception e) {
            // 记忆是"锦上添花"，任何失败都不能影响报告/对话主流程
            log.warn("记忆提取异常（已忽略），user={}", userId, e);
            return null;
        }
    }

    /**
     * 档案合并规则：模型返回了哪个字段（非空）就覆盖哪个字段，没返回的保持原样。
     * 数组字段是整体替换不是并集——这是刻意的：模型被要求输出更新后的完整列表，
     * "室友（关系紧张）"才能被"室友（已和好）"替换掉，而不是两条并存。
     */
    void mergeProfile(String userId, UserProfile current,
            ProfileUpdates upd, String cardId) {
        if (upd == null) {
            return;
        }
        UserProfile base = current == null
                ? new UserProfile("", "", List.of(), List.of(), "", "", List.of())
                : current;

        String nickname = isBlank(upd.nickname()) ? base.nickname() : upd.nickname();
        String background = isBlank(upd.background()) ? base.background() : upd.background();
        List<String> concerns = upd.concerns() == null ? base.concerns() : upd.concerns();
        List<String> relations = upd.relations() == null ? base.relations() : upd.relations();
        String style = isBlank(upd.style()) ? base.style() : upd.style();

        // sourceCards 追加本次来源卡 ID（去重，保持最新在前）
        List<String> sources = new ArrayList<>();
        if (cardId != null) {
            sources.add(cardId);
        }
        if (base.sourceCards() != null) {
            for (String s : base.sourceCards()) {
                if (!sources.contains(s)) {
                    sources.add(s);
                }
            }
        }

        UserProfile merged = new UserProfile(nickname, background, concerns,
                relations, style, LocalDateTime.now().format(FMT), sources);
        try {
            redis.opsForValue().set(KEY_PROFILE + userId,
                    objectMapper.writeValueAsString(merged));
        } catch (Exception e) {
            log.warn("档案合并失败（已忽略），user={}", userId, e);
        }
    }

    /**
     * 把档案、最近 5 张记忆卡、未完结话题渲染成一段文字，拼进 system prompt。
     * 没有任何记忆时返回空字符串（一个字都不加）。
     */
    public String buildMemoryPrompt(String userId) {
        try {
            UserProfile profile = readProfile(userId);
            List<MemoryCard> cards = recentCards(userId, 5);
            List<String> threads = readOpenThreads(userId);
            if (profile == null && cards.isEmpty() && threads.isEmpty()) {
                return "";
            }

            StringBuilder sb = new StringBuilder("\n\n<用户长期记忆>\n");
            if (profile != null) {
                sb.append("【用户档案】");
                if (!isBlank(profile.nickname())) {
                    sb.append("称呼：").append(profile.nickname()).append("；");
                }
                if (!isBlank(profile.background())) {
                    sb.append("背景：").append(profile.background()).append("；");
                }
                if (profile.concerns() != null && !profile.concerns().isEmpty()) {
                    sb.append("当前困扰：").append(String.join("、", profile.concerns())).append("；");
                }
                if (profile.relations() != null && !profile.relations().isEmpty()) {
                    sb.append("重要关系：").append(String.join("、", profile.relations())).append("；");
                }
                if (!isBlank(profile.style())) {
                    sb.append("沟通风格：").append(profile.style());
                }
                sb.append("\n");
            }
            if (!cards.isEmpty()) {
                sb.append("【最近对话记忆】（新在前）\n");
                for (MemoryCard c : cards) {
                    sb.append("- [").append(c.createdAt()).append("] 情绪分 ")
                      .append(c.moodScore()).append("/10：").append(c.summary()).append("\n");
                }
            }
            if (!threads.isEmpty()) {
                sb.append("【未完结话题】（可自然地主动关心进展，不要生硬追问）\n");
                for (String t : threads) {
                    sb.append("- ").append(t).append("\n");
                }
            }
            sb.append("</用户长期记忆>");
            return sb.toString();
        } catch (Exception e) {
            log.warn("构建记忆提示词失败（按无记忆处理），user={}", userId, e);
            return "";
        }
    }

    // ===== 读取方法 =====

    /** 读累积档案，没有返回 null */
    public UserProfile readProfile(String userId) {
        try {
            String json = redis.opsForValue().get(KEY_PROFILE + userId);
            return json == null ? null : objectMapper.readValue(json, UserProfile.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** 最近 count 张记忆卡，新的在前 */
    public List<MemoryCard> recentCards(String userId, int count) {
        List<MemoryCard> cards = new ArrayList<>();
        try {
            List<String> ids = redis.opsForList().range(KEY_INDEX + userId, 0, count - 1L);
            if (ids == null) {
                return cards;
            }
            for (String id : ids) {
                String json = redis.opsForValue().get(KEY_CARD + id);
                if (json != null) {
                    cards.add(objectMapper.readValue(json, MemoryCard.class));
                }
            }
        } catch (Exception e) {
            log.warn("读取记忆卡失败，user={}", userId, e);
        }
        return cards;
    }

    /** 当前未完结话题 */
    public List<String> readOpenThreads(String userId) {
        try {
            String json = redis.opsForValue().get(KEY_THREADS + userId);
            if (json == null) {
                return List.of();
            }
            List<String> threads = new ArrayList<>();
            objectMapper.readTree(json).forEach(n -> threads.add(n.asText()));
            return threads;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 清空某用户全部记忆：先删所有记忆卡本体，再删索引、档案、话题，必须删干净 */
    public void clearAll(String userId) {
        List<String> ids = redis.opsForList().range(KEY_INDEX + userId, 0, -1);
        if (ids != null) {
            for (String id : ids) {
                redis.delete(KEY_CARD + id);
            }
        }
        redis.delete(KEY_INDEX + userId);
        redis.delete(KEY_PROFILE + userId);
        redis.delete(KEY_THREADS + userId);
        log.info("已清空用户全部记忆 user={}", userId);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private String toJson(UserProfile profile) {
        try {
            return objectMapper.writeValueAsString(profile);
        } catch (Exception e) {
            return "{}";
        }
    }
}
