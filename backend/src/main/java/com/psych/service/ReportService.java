package com.psych.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.psych.dto.ChatMessage;
import com.psych.dto.ReadinessData;
import com.psych.dto.ReportData;
import com.psych.dto.ReportToolArgs;
import com.psych.exception.BizException;
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
 * 心理分析报告服务：用 DeepSeek 的 Function Calling（工具调用）拿到结构化 JSON 报告。
 *
 * 核心思路（这是行业里"让大模型稳定输出结构化数据"的标准做法）：
 *   1. 定义工具 schema  —— 告诉模型"你要输出哪些字段、什么类型"
 *   2. tool_choice 强制 —— 不许模型自由发挥，必须调用这个工具
 *   3. parse arguments  —— 工具调用参数是 JSON 字符串，解析成 Java 对象
 *   4. 整份存 Redis     —— report:{id} 存报告本体，user_reports:{userId} 存索引列表
 */
@Service
public class ReportService {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final MemoryService memoryService;
    private final KnowledgeService knowledgeService;
    private final CrisisDetector crisisDetector;

    @Value("${app.deepseek.base-url}")
    private String baseUrl;

    @Value("${app.deepseek.api-key}")
    private String apiKey;

    @Value("${app.deepseek.model}")
    private String model;

    public ReportService(StringRedisTemplate redis, ObjectMapper objectMapper,
            MemoryService memoryService, KnowledgeService knowledgeService,
            CrisisDetector crisisDetector) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.memoryService = memoryService;
        this.knowledgeService = knowledgeService;
        this.crisisDetector = crisisDetector;
    }

    /**
     * 报告生成的系统提示词。
     * 旧实现完全不给 system 提示，报告质量全看模型自由发挥；
     * 现在明确三件事：不许做诊断、严重程度必须写依据、风险必须在最前面。
     */
    private static final String REPORT_SYSTEM_PROMPT = """
            你是一名受过专业训练的心理健康分析师，正在为一位用户生成结构化的心理状态分析报告。

            # 硬性要求
            1. **这不是医学诊断。** 禁止给出"抑郁症""焦虑症""双相"等疾病诊断，
               只能描述当前状态、主要困扰与严重程度倾向。
            2. **严重程度只能在 轻度 / 中度 / 重度 三档中选择**，且 severityReason 必须写出判断依据：
               症状持续了多久、对工作学习社交睡眠的影响程度，并引用下面【量表与阈值】里给出的
               临界值及其来源（哪项研究、哪个人群、哪种参照标准）。不许只给结论不给依据。
            3. **风险优先。** 如果对话中出现自伤、自杀或伤人的想法（无论是否付诸行动），
               riskFlag 必须为 true，并在 narrative 的最前面写安全提示与求助渠道，
               再写状态概述等内容。
            4. **不编造。** 对话里没出现的信息不要补充；信息不足的部分明确写"目前信息不足以判断"。
            5. narrative 用中文 Markdown，结构为：状态概述 / 严重程度判断（含依据与来源）/
               专业建议与措施 / 后续支持。
            6. 建议要具体到可执行的动作，不要"多休息、放松心情"这类空话；
               不推荐具体药物，不指导调整或停止用药。
            7. 结尾说明这只是心理健康参考，不能替代精神科医生的诊断与治疗。

            # 禁止的表达
            - 不评判、不比较、不说教（"你就是想太多""别人比你更惨"）。
            - 不承诺疗效，不保证"聊一聊就能好"。
            """;

    // ===== 第 1 步：定义工具 schema（JSON Schema 描述报告的字段与类型）=====
    private static final String REPORT_TOOLS = """
            [ {
              "type": "function",
              "function": {
                "name": "generate_psych_report",
                "description": "根据此前的咨询对话，生成结构化的心理分析报告",
                "parameters": {
                  "type": "object",
                  "properties": {
                    "narrative": {
                      "type": "string",
                      "description": "完整心理分析报告正文，Markdown 格式，包含：状态概述、严重程度判断、专业建议与措施、后续支持说明"
                    },
                    "stateSummary": {
                      "type": "string",
                      "description": "一句话通俗概括用户当前的核心心理状态"
                    },
                    "severity": {
                      "type": "string",
                      "enum": ["轻度", "中度", "重度"],
                      "description": "问题严重程度等级"
                    },
                    "severityReason": {
                      "type": "string",
                      "description": "作出该严重程度判断的依据，1-2 句话"
                    },
                    "emotionTags": {
                      "type": "array",
                      "items": { "type": "string" },
                      "description": "用户当前的主要情绪标签，如 焦虑、失眠、压力"
                    },
                    "suggestions": {
                      "type": "array",
                      "items": { "type": "string" },
                      "description": "针对该严重程度的具体可操作建议，3-6 条"
                    },
                    "riskFlag": {
                      "type": "boolean",
                      "description": "对话中是否出现自伤、自杀、伤人等高危风险信号"
                    }
                  },
                  "required": ["narrative", "stateSummary", "severity", "severityReason", "emotionTags", "suggestions", "riskFlag"],
                  "additionalProperties": false
                },
                "strict": true
              }
            } ]
            """;

    // 生成报告时追加的最后一条指令
    private static final String REPORT_INSTRUCTION =
            "请基于以上全部对话内容，立即调用 generate_psych_report 工具，"
            + "生成最终的结构化心理分析报告。报告正文请使用中文 Markdown。";

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 判断模型正文里是否已经给出求助渠道（给了就不重复补安全提示） */
    private static final List<String> HOTLINE_MARKERS =
            List.of("12356", "010-82951332", "400-161-9995", "120", "110");

    // ===== AI 评分体制的规则常量 =====
    /** 评分阈值：信息完整度达到 70 分才允许生成报告（前后端同一标准） */
    public static final int MIN_SCORE = 70;

    // 评估信息完整度的工具 schema（和报告工具同一套 Function Calling 机制）
    private static final String ASSESS_TOOLS = """
            [ {
              "type": "function",
              "function": {
                "name": "assess_readiness",
                "description": "评估当前咨询对话的信息完整度，判断能否生成心理分析报告",
                "parameters": {
                  "type": "object",
                  "properties": {
                    "score": {
                      "type": "integer",
                      "description": "信息完整度评分 0-100。评分维度：情绪困扰的具体表现、持续时间、对日常生活的影响、触发因素、过往应对方式、社会支持系统，每个维度约 15-20 分"
                    },
                    "reason": {
                      "type": "string",
                      "description": "评分理由，一句话说明已了解到什么、还缺什么"
                    },
                    "missingInfo": {
                      "type": "array",
                      "items": { "type": "string" },
                      "description": "还缺少的关键信息项，如 持续时间、社会支持"
                    }
                  },
                  "required": ["score", "reason"],
                  "additionalProperties": false
                },
                "strict": true
              }
            } ]
            """;

    private static final String ASSESS_INSTRUCTION =
            "请根据以上全部对话内容，立即调用 assess_readiness 工具评估信息完整度。"
            + "评估维度：情绪困扰的具体表现、持续时间、对日常生活的影响、触发因素、"
            + "过往应对方式、社会支持系统。信息不足以生成可靠报告时请打低分。";

    /**
     * 用一段对话历史生成报告：调模型 → 强制工具调用 → 解析 JSON → 存 Redis。
     */
    public ReportData generateReport(String userId, List<ChatMessage> history) {
        try {
            // 0a. 硬性门槛一：报告按天生成，前端只传"今天"的聊天记录，内容不能为空
            long userTurns = history == null ? 0
                    : history.stream().filter(m -> "user".equals(m.role())).count();
            if (userTurns == 0) {
                throw new BizException("今天还没有可生成报告的聊天记录，先和 AI 心理助手聊聊吧");
            }

            // 0b. 硬性门槛二：AI 评估信息完整度 ≥ MIN_SCORE 才放行（后端自己再评一次，
            //     不信任前端传的状态；评估有约 1.5 秒的模型调用开销，可接受）
            ReadinessData readiness = assess(history);
            if (!readiness.ready()) {
                String missing = readiness.missingInfo() == null || readiness.missingInfo().isEmpty()
                        ? ""
                        : "缺失信息：" + String.join("、", readiness.missingInfo()) + "。";
                throw new BizException("信息完整度 " + readiness.score()
                        + " 分（需 ≥ " + MIN_SCORE + "），暂时无法生成报告。" + missing
                        + "请继续聊聊再试");
            }

            // 0c. 风险标记不走模型判断：先用关键词规则扫一遍对话摘要，命中即强制标记。
            //     模型可能漏判或淡化风险，而"有没有自杀风险"是这份报告里最不能出错的一项。
            String digest = conversationDigest(history);
            CrisisDetector.Signal risk = crisisDetector.scan(digest);

            // 1. 组装请求体：历史消息 + 生成指令
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", model);
            // deepseek-flash 是思考模型：思考模式不支持强制 tool_choice，须关闭思考
            body.putObject("thinking").put("type", "disabled");

            ArrayNode messages = body.putArray("messages");
            // 系统提示 + 分层知识：报告从"模型自由发挥"变成"照着专业知识标准写"
            messages.addObject().put("role", "system")
                    .put("content", REPORT_SYSTEM_PROMPT + buildReportKnowledge(digest, risk));
            if (history != null) {
                for (ChatMessage m : history) {
                    ObjectNode node = messages.addObject();
                    node.put("role", m.role());
                    node.put("content", m.content());
                }
            }
            messages.addObject().put("role", "user").put("content", REPORT_INSTRUCTION);

            // 2. 挂上工具定义（schema 里的 JSON 字符串转成 JSON 节点）
            body.set("tools", objectMapper.readTree(REPORT_TOOLS));

            // 3. tool_choice 强制：不许自由回答，必须调用指定工具
            ObjectNode toolChoice = body.putObject("tool_choice");
            toolChoice.put("type", "function");
            toolChoice.putObject("function").put("name", "generate_psych_report");

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
                throw new BizException("模型服务返回异常（HTTP " + response.statusCode() + "）");
            }

            // 4. 从响应里取 tool_calls，arguments 是 JSON 字符串，解析成对象
            JsonNode toolCalls = objectMapper.readTree(response.body())
                    .path("choices").path(0).path("message").path("tool_calls");
            if (!toolCalls.isArray() || toolCalls.isEmpty()) {
                throw new BizException("模型未返回报告数据，请重试");
            }
            String args = toolCalls.get(0)
                    .path("function").path("arguments").asText();
            ReportToolArgs tool = objectMapper.readValue(args, ReportToolArgs.class);

            // 5. 补上系统元数据，形成完整报告
            String narrative = tool.narrative() == null ? "" : tool.narrative();
            boolean riskFlag = Boolean.TRUE.equals(tool.riskFlag());
            if (risk.triggered()) {
                // 规则命中即强制为 true；HIGH 级且正文没给求助渠道时，把安全提示补到正文最前面
                riskFlag = true;
                if (risk.level() == CrisisDetector.Level.HIGH && !containsHotline(narrative)) {
                    narrative = safetyHeader(risk) + "\n\n" + narrative;
                }
            }
            ReportData report = new ReportData(
                    UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                    userId,
                    LocalDateTime.now().format(FMT),
                    tool.stateSummary(),
                    tool.severity(),
                    tool.severityReason(),
                    tool.emotionTags() == null ? List.of() : tool.emotionTags(),
                    tool.suggestions() == null ? List.of() : tool.suggestions(),
                    riskFlag,
                    narrative);

            // 6. 整份存 Redis：本体 + 用户索引列表（都没有 TTL，报告长期保留）
            redis.opsForValue().set("report:" + report.reportId(),
                    objectMapper.writeValueAsString(report));
            redis.opsForList().leftPush("user_reports:" + userId, report.reportId());

            // 7. 报告成功后顺手提取记忆（档案 + 记忆卡 + 话题）。
            //    capture 内部 catch-all 只打日志，绝不影响已成功的报告；
            //    同线程串行执行，符合本期的简单性要求。
            memoryService.capture(userId, history);

            return report;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("生成报告失败，请稍后再试");
        }
    }

    /**
     * AI 评分体制：让模型评估对话信息完整度（0-100）。
     * 同样走 Function Calling 强制输出，保证结果是结构化可解析的。
     * ready 不听模型的，由后端按 MIN_SCORE 阈值统一判定。
     */
    public ReadinessData assess(List<ChatMessage> history) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", model);

            ArrayNode messages = body.putArray("messages");
            // 评估"信息够不够"时，把 C 层访谈要点给模型当对照清单，
            // 避免它凭感觉打分（缺社会支持、缺既往史这类项容易漏问）
            messages.addObject().put("role", "system").put("content",
                    "你是心理健康访谈的督导。请严格按《评估访谈要点》的六个维度检查信息完整度。\n"
                            + interviewChecklist(history));
            if (history != null) {
                for (ChatMessage m : history) {
                    ObjectNode node = messages.addObject();
                    node.put("role", m.role());
                    node.put("content", m.content());
                }
            }
            messages.addObject().put("role", "user").put("content", ASSESS_INSTRUCTION);

            // 评估是简单结构化任务，同样关闭思考以支持强制工具调用
            body.putObject("thinking").put("type", "disabled");

            body.set("tools", objectMapper.readTree(ASSESS_TOOLS));
            ObjectNode toolChoice = body.putObject("tool_choice");
            toolChoice.put("type", "function");
            toolChoice.putObject("function").put("name", "assess_readiness");

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new BizException("模型服务返回异常（HTTP " + response.statusCode() + "）");
            }

            JsonNode toolCalls = objectMapper.readTree(response.body())
                    .path("choices").path(0).path("message").path("tool_calls");
            if (!toolCalls.isArray() || toolCalls.isEmpty()) {
                throw new BizException("模型未返回评估结果，请重试");
            }
            JsonNode args = objectMapper.readTree(
                    toolCalls.get(0).path("function").path("arguments").asText());

            int score = Math.max(0, Math.min(100, args.path("score").asInt(0)));
            List<String> missing = new ArrayList<>();
            JsonNode missingNode = args.path("missingInfo");
            if (missingNode.isArray()) {
                missingNode.forEach(n -> missing.add(n.asText()));
            }
            return new ReadinessData(
                    score,
                    score >= MIN_SCORE, // 阈值判定在后端，规则确定
                    args.path("reason").asText(""),
                    missing);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("评估失败，请稍后再试");
        }
    }

    /**
     * 生成报告用的知识上下文：按对话摘要检索各层知识。
     * 报告是"下判断"的场景，所以量表阈值和风险规程必给；论文片段少给（只用于佐证）。
     */
    private String buildReportKnowledge(String summary, CrisisDetector.Signal risk) {
        StringBuilder sb = new StringBuilder();
        try {
            appendReportGroup(sb, "量表与阈值（严重程度判断依据，必须引用来源）",
                    knowledgeService.searchLayer(summary, KnowledgeService.LAYER_SCALE, 3));
            if (risk.triggered()) {
                appendReportGroup(sb, "风险与危机（强制规程）",
                        knowledgeService.layerChunks(KnowledgeService.LAYER_RISK, 12));
            }
            appendReportGroup(sb, "干预建议",
                    knowledgeService.searchLayer(summary, KnowledgeService.LAYER_ADVICE, 2));
            appendReportGroup(sb, "评估访谈要点",
                    knowledgeService.layerChunks(KnowledgeService.LAYER_INTERVIEW, 8));
            appendReportGroup(sb, "原始论文片段（佐证，不要编造其中的数字）",
                    knowledgeService.searchPapers(summary));
        } catch (Exception e) {
            return "";
        }
        if (!risk.triggered()) {
            return sb.toString();
        }
        return sb.append("\n\n⚠️ 风险提示：本段对话检测到风险信号（命中：")
                .append(String.join("、", risk.matched()))
                .append("）。riskFlag 必须为 true，并把安全提示与求助渠道写在报告最前面。")
                .toString();
    }

    /** 评估信息完整度时的对照清单 */
    private String interviewChecklist(List<ChatMessage> history) {
        try {
            List<KnowledgeService.SearchHit> hits =
                    knowledgeService.layerChunks(KnowledgeService.LAYER_INTERVIEW, 8);
            StringBuilder sb = new StringBuilder();
            appendReportGroup(sb, "评估访谈要点", hits);
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private void appendReportGroup(StringBuilder sb, String title, List<KnowledgeService.SearchHit> hits) {
        if (hits.isEmpty()) {
            return;
        }
        sb.append("\n## ").append(title).append('\n');
        for (KnowledgeService.SearchHit hit : hits) {
            sb.append("\n来源：").append(hit.title());
            if (KnowledgeService.LAYER_PAPER.equals(hit.layer())) {
                String note = KnowledgeService.paperNote(hit.title());
                if (!note.isEmpty()) {
                    sb.append("（").append(note).append("）");
                }
            }
            sb.append('\n').append(hit.text()).append('\n');
        }
    }

    /** 把对话压成一段用于检索的摘要（最近 8 条用户发言，截断到 800 字） */
    private String conversationDigest(List<ChatMessage> history) {
        if (history == null || history.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (int i = history.size() - 1; i >= 0 && parts.size() < 8; i--) {
            ChatMessage m = history.get(i);
            if ("user".equals(m.role())) {
                parts.add(0, m.content());
            }
        }
        String joined = String.join("\n", parts);
        return joined.length() > 800 ? joined.substring(joined.length() - 800) : joined;
    }

    /** 正文里是否已经出现任何一条求助渠道 */
    private static boolean containsHotline(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (String marker : HOTLINE_MARKERS) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /** 模型漏写求助渠道时，补在报告最前面的安全提示 */
    private static String safetyHeader(CrisisDetector.Signal risk) {
        return "> ⚠️ **安全提示**：本次对话中出现了高风险表达（" + String.join("、", risk.matched())
                + "）。你的安全是最重要的。\n"
                + "> - 全国统一心理援助热线：**12356**（24 小时）\n"
                + "> - 北京心理危机研究与干预中心：**010-82951332**\n"
                + "> - 希望 24 热线：**400-161-9995**\n"
                + "> - 紧急情况请拨打 **120**（急救）或 **110**（报警）\n"
                + "> 请尽量不要独处，待在有人陪伴的地方，暂时远离可能造成伤害的物品。";
    }

    /** 查询某用户的全部报告（新报告在前） */
    public List<ReportData> listReports(String userId) {
        List<String> ids = redis.opsForList().range("user_reports:" + userId, 0, -1);
        List<ReportData> reports = new ArrayList<>();
        if (ids == null) {
            return reports;
        }
        for (String id : ids) {
            ReportData r = readOne(id);
            if (r != null) {
                reports.add(r);
            }
        }
        return reports;
    }

    /** 查单份报告，并校验归属（不能看别人的报告） */
    public ReportData getReport(String userId, String reportId) {
        ReportData report = readOne(reportId);
        if (report == null) {
            throw new BizException("报告不存在");
        }
        if (!report.userId().equals(userId)) {
            throw new BizException("无权查看该报告");
        }
        return report;
    }

    /** 从 Redis 读一份报告 JSON 并还原成对象，键不存在返回 null */
    private ReportData readOne(String reportId) {
        try {
            String json = redis.opsForValue().get("report:" + reportId);
            return json == null ? null : objectMapper.readValue(json, ReportData.class);
        } catch (Exception e) {
            return null; // 单份坏数据不影响列表其他报告
        }
    }
}
