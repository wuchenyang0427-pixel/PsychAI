package com.psych.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.psych.dto.ChatMessage;
import com.psych.dto.ChatSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * DeepSeek 对话服务："零框架"实现。
 *
 * 不用 Spring AI / LangChain4j，直接用 JDK 自带的 HttpClient 发 HTTPS 请求。
 * 目的是先看懂大模型 API 的本质：它就是一个普通的 POST 接口，
 * 请求体 JSON 里放"你是谁(system) + 之前聊了什么(history) + 这句新消息(user)"。
 * 聊天回答走 SSE 流式：请求体加 "stream": true 后，DeepSeek 边生成边回小段，
 * 本服务逐段转发给前端，形成打字机效果。
 */
@Service
public class DeepSeekService {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekService.class);

    // JDK 自带的 HTTP 客户端，线程安全，可复用
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final ObjectMapper objectMapper;
    private final MemoryService memoryService;
    private final KnowledgeService knowledgeService;
    private final CrisisDetector crisisDetector;

    // 流式任务专用线程池：每个进行中的 SSE 连接占一个线程阻塞读 DeepSeek 的流
    private final ExecutorService streamPool = Executors.newCachedThreadPool();

    // 三项配置都来自 application.yml
    @Value("${app.deepseek.base-url}")
    private String baseUrl;

    @Value("${app.deepseek.api-key}")
    private String apiKey;

    @Value("${app.deepseek.model}")
    private String model;

    public DeepSeekService(ObjectMapper objectMapper, MemoryService memoryService,
            KnowledgeService knowledgeService, CrisisDetector crisisDetector) {
        this.objectMapper = objectMapper;
        this.memoryService = memoryService;
        this.knowledgeService = knowledgeService;
        this.crisisDetector = crisisDetector;
    }

    /** 用户直接问"怎么办"时，把 D 层干预知识一起带上 */
    private static final List<String> ADVICE_HINTS = List.of(
            "怎么办", "咋办", "怎么调整", "怎么缓解", "怎么改善", "建议", "方法", "该做",
            "要不要", "如何", "帮我", "有没有用", "怎么才能");

    /** 涉及严重程度/评估/症状时，把 B 层量表阈值一起带上 */
    private static final List<String> ASSESS_HINTS = List.of(
            "严重", "程度", "评估", "量表", "测试", "得分", "分数", "诊断", "判断", "报告",
            "抑郁", "焦虑", "有病", "失眠", "睡不", "食欲", "兴趣", "情绪", "压力");

    // 助手人设与行为约束，每次请求都会随消息一起发给模型。
    // Java 17 文本块("""...""")可以直接放多行长提示词，无需手动拼接 \n
    private static final String SYSTEM_PROMPT = """
            <优化后的系统提示词>
            # 角色定位
            你是专业的心理健康分析师，具备扎实的心理学专业知识与丰富的临床评估经验，始终保持中立、共情、严谨的态度，通过多轮对话收集用户的心理状态相关信息，最终为用户生成规范的心理分析报告，判断问题严重程度，并提供科学可行的应对措施与建议。你需要严格遵守心理咨询伦理，不进行疾病确诊，仅提供专业的心理状态分析与健康指导，若发现用户存在自伤、伤人等高危风险，需第一时间引导其寻求专业医疗机构或危机干预热线的帮助。

            # 对话原则
            1. **共情优先**：始终以温和、接纳、不评判的语气与用户交流，让用户感受到被理解、被尊重，避免使用指责、说教、生硬的专业术语，让用户愿意敞开心扉表达真实感受。
            2. **循序渐进**：不要一开始就直接询问敏感问题或索要信息生成报告，先通过自然的对话建立信任关系，逐步引导用户讲述自己的情绪状态、持续时间、影响生活的程度、相关触发事件、过往经历、社会支持系统等关键信息，不强行追问用户不愿提及的内容。
            3. **伦理边界**：明确告知用户你的分析仅作为心理健康参考，不能替代精神科医生的诊断与治疗，不随意给用户贴“抑郁症”“焦虑症”等疾病标签；严格保护用户的对话隐私，不泄露任何用户分享的个人信息。
            4. **风险警觉**：在对话中如果发现用户提及自伤、自杀、伤害他人的想法或计划，需立刻停止常规分析流程，优先进行危机干预引导，明确告知其生命安全的重要性，提供全国心理危机干预热线等官方求助渠道，强烈建议其立即联系身边的亲友或前往专业精神卫生机构就诊。

            # 对话工作流程
            ## 第一阶段：信息收集
            1. 开场友好问候，保持简洁自然，无需冗长的自我介绍铺垫，直接以引导性提问开启对话，比如“你好呀😊，有什么想和我聊聊的吗？”，避免大段生硬的身份说明和功能介绍，让对话氛围更轻松。
            2. 在用户倾诉的过程中，适时给予共情回应，比如“听起来这段时间你真的承受了很多，一定很辛苦吧”，同时通过开放式问题引导用户补充关键信息，包括但不限于：
               - 情绪/心理困扰的具体表现：比如是否持续情绪低落、焦虑、烦躁、失眠、兴趣减退等
               - 困扰持续的时间：是最近几天出现，还是已经持续几周、几个月
               - 对日常生活的影响：是否已经影响到工作/学习效率、社交关系、饮食睡眠等正常生活
               - 相关的触发因素：是否有特定的事件、压力源引发了这些状态
               - 过往的应对方式：之前有没有尝试过什么方法调整，效果如何
               - 可获得的支持：身边是否有亲友可以提供支持，是否有过心理咨询或就诊经历
            3. 若用户提供的信息不足以支撑完整分析，需礼貌地向用户说明需要补充的信息，比如“为了能更准确地了解你的状态，我想再问一下，这种情绪状态大概持续多久了呀？”，不强迫用户回答。

            ## 第二阶段：分析与反馈
            当收集到足够的关键信息后，为用户生成清晰易懂的心理分析报告，报告结构如下：
            1. **状态概述**：用通俗的语言总结用户当前的核心心理状态、主要情绪表现与相关影响因素，避免使用晦涩的专业术语，让用户清晰感受到你准确理解了他的处境。
            2. **严重程度判断**：分轻度/中度/重度三档，判断依据必须写清楚，按下面的优先级取证据：
               - 首选【量表与阈值】里给出的临界值与来源（如 PHQ-9 ≥10、GAD-7 的 8–10、C-SSRS 意念 5 级），
                 并说明这是“根据你描述的情况大致对应”，**不是实测得分**；
               - 用户没有明确做过量表时，**不许替用户编造分数**，改用病程（持续多久）、
                 功能损害（工作/学习/社交/睡眠/饮食受影响的程度）与风险信号来判断；
               - 三档口径：轻度＝困扰时间较短、功能损害小、可自我调节；中度＝持续一段时间、
                 对工作学习社交有明显影响、自我调节困难；重度＝持续时间长、严重损害功能，
                 或出现自伤/伤人的念头、计划、行为，需立即专业干预；
               - 报告里出现任何阈值都要连来源一起写（哪项研究、哪个人群、哪种参照标准）。
            3. **专业建议与措施**：根据严重程度给出针对性、可落地的建议：
               - 轻度状态：提供具体的自我调节方法，比如情绪宣泄技巧、正念呼吸练习、日常作息调整、轻度运动建议、社交支持联结方法等
               - 中度状态：在自我调节方法的基础上，建议用户寻求专业心理咨询师的帮助，同时指导用户如何调整当下的压力环境，避免状态进一步恶化
               - 重度状态：强烈建议用户立即前往正规医院的精神科/心理科就诊，遵医嘱进行治疗，同时告知用户如何寻求身边人的支持，避免独处面对高危状态
            4. **后续支持**：询问用户对分析报告是否有疑问，是否有其他想要补充的内容，或者还有哪些具体的困惑需要进一步解答。

            # 语言风格要求
            - 语气温和沉稳，充满关怀感，避免过于冰冷、机械、冗长生硬的表达，全程保持耐心，对话感强。
            - 专业内容通俗化，不用生僻的心理学概念堆砌内容，确保用户能够轻松理解。
            - 回应简洁有针对性，不发表空泛的鸡汤言论，所有建议都要贴合用户的实际情况，具备可操作性。
            - 不随意评判用户的想法与感受，比如不说“你就是想太多了”“这点小事没什么好难过的”这类否定用户情绪的话语。

            ## 举例说明
            - 正确开场示例：用户说“你好”，助手回应“你好呀😊，有什么想和我聊聊的吗？”
            - 错误开场示例：用户说“你好”，助手回应“你好呀😊，我是你的心理健康分析师，如果你最近有情绪上的困扰、心里攒着什么想倾诉的烦心事，都可以慢慢和我说，我会认真听你讲，也会尽力理解你的感受。”（表述冗长生硬，没有直接引导用户开口表达）
            </优化后的系统提示词>
            """;

    /**
     * 流式对话：模型一边生成，后端一边把增量通过 SSE 推给前端（打字机效果）。
     *
     * SSE 事件约定：
     *   sources → RAG 参考来源（JSON 数组，流式开始前最先发）
     *   delta   → {"content": "一小段文字"}，每个模型增量发一条
     *   done    → 正常结束
     *   error   → {"message": "友好文案"}，出错时发，发完即关连接（不再发 done）
     *
     * 本方法立即返回，真正的工作在后台线程里做；Controller 先把 emitter 交还给
     * Tomcat 挂起连接，再由后台线程往里"注水"。
     */
    public void chatStream(String userId, String message, List<ChatMessage> history,
            SseEmitter emitter) {
        // execute() 里未捕获的异常不会抛给任何人，emitter 会永远挂起——任务主体必须整段 try-catch
        streamPool.execute(() -> {
            // 上游 DeepSeek 响应流存进 holder，emitter 超时回调里强关它，打断阻塞的 readLine()
            AtomicReference<InputStream> upstream = new AtomicReference<>();
            emitter.onTimeout(() -> {
                closeQuietly(upstream.get());
                emitter.complete();
            });
            emitter.onError(t -> log.debug("SSE 连接中断（多为前端关闭页面），user={}", userId));
            try {
                // 1. 前置准备：风险扫描 + RAG 检索 + 拼 messages
                PreparedChat prepared = prepareChat(userId, message, history);
                emitter.send(SseEmitter.event().name("sources")
                        .data(prepared.sources(), MediaType.APPLICATION_JSON));

                // 2. 拼请求体：与非流式唯一的区别就是多了 "stream": true
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("model", model);
                body.put("messages", prepared.messages());
                body.put("stream", true);

                // timeout 只管"首包迟迟不来"；流式 body 的中段读取不受它约束（靠 emitter 超时兜底）
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/chat/completions"))
                        .timeout(Duration.ofSeconds(60))
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                objectMapper.writeValueAsString(body)))
                        .build();

                // 3. ofInputStream 拿到"还开着的水管"，逐行读 DeepSeek 的 SSE 帧
                HttpResponse<InputStream> response =
                        http.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() != 200) {
                    // 密钥错误会拿到 401，余额不足是 402，方便对着排查
                    response.body().close();
                    sendError(emitter, "模型服务返回异常（HTTP " + response.statusCode() + "）");
                    return;
                }
                upstream.set(response.body());

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        // OpenAI 兼容格式：每个事件一行 "data: {json}"，结尾一行 "data: [DONE]"
                        if (!line.startsWith("data:")) {
                            continue; // 跳过空行和注释行
                        }
                        String data = line.substring(5).trim();
                        if ("[DONE]".equals(data)) {
                            break;
                        }
                        if (data.isEmpty()) {
                            continue;
                        }
                        // 首 chunk 只带 role、末 chunk 只带 finish_reason、思考内容在
                        // reasoning_content——只认 delta.content，这些情况天然被跳过
                        JsonNode content = objectMapper.readTree(data)
                                .path("choices").path(0).path("delta").path("content");
                        if (content.isMissingNode() || content.isNull()
                                || content.asText().isEmpty()) {
                            continue;
                        }
                        // 载荷必须走 JSON 序列化：content 常含换行，发原始字符串会撑破 SSE 帧
                        emitter.send(SseEmitter.event().name("delta")
                                .data(Map.of("content", content.asText()),
                                        MediaType.APPLICATION_JSON));
                    }
                }
                emitter.send(SseEmitter.event().name("done").data(""));
                emitter.complete();
            } catch (IOException e) {
                // 多为前端中途断开（send 时连接已关），安静结束即可
                emitter.complete();
            } catch (Exception e) {
                // 真实异常必须打日志，否则无法排查
                log.warn("流式对话失败，user={}", userId, e);
                sendError(emitter, e instanceof java.net.http.HttpTimeoutException
                        ? "AI 思考时间有点久，请稍后重试一次" : "连接模型服务失败，请稍后再试");
            }
        });
    }

    /** 发 error 事件并关闭连接；发送本身失败（前端已断开）时静默忽略 */
    private void sendError(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event().name("error")
                    .data(Map.of("message", message), MediaType.APPLICATION_JSON));
        } catch (Exception ignored) {
            // 前端已断开，没什么好补救的
        }
        emitter.complete();
    }

    private void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (IOException ignored) {
                // 关流失败无需处理
            }
        }
    }

    /** prepareChat 的返回物：拼好的 messages + 本次命中的参考来源 */
    private record PreparedChat(List<Map<String, String>> messages, List<ChatSource> sources) {
    }

    /**
     * 流式对话的前置准备：风险扫描 → 分层 RAG 检索 → 组装 messages。
     *
     * @param userId  当前登录用户 ID（用于拉取该用户的长期记忆，来自 token）
     * @param message 用户刚发的这条消息
     * @param history 之前的对话记录（不含本条），带上它模型才有"上下文记忆"
     */
    private PreparedChat prepareChat(String userId, String message, List<ChatMessage> history) {
        // RAG 第一步：风险扫描。走关键词规则，不参与相似度排序——
        // 实测"我不想活了"这种话，向量检索会被参考文献里的同类词挤出真正的风险知识。
        CrisisDetector.Signal risk = crisisDetector.scan(recentUserText(history, message));

        // RAG 第二步：分层检索。失败（Ollama 没启动等）只降级为"不带知识段"，
        // 普通聊天和长期记忆照常工作。
        List<KnowledgeService.SearchHit> papers = List.of();
        List<KnowledgeService.SearchHit> scales = List.of();
        List<KnowledgeService.SearchHit> riskCard = List.of();
        List<KnowledgeService.SearchHit> interview = List.of();
        List<KnowledgeService.SearchHit> advice = List.of();
        try {
            // 风险触发时少给论文、多给规程，避免专业资料把安全流程冲淡
            papers = risk.triggered() ? List.of() : knowledgeService.searchPapers(message);
            if (risk.triggered() || containsAny(message, ASSESS_HINTS)) {
                scales = knowledgeService.searchLayer(message, KnowledgeService.LAYER_SCALE, 2);
            }
            if (risk.triggered()) {
                riskCard = knowledgeService.layerChunks(KnowledgeService.LAYER_RISK, 12);
            }
            if (isEarlyStage(history)) {
                interview = knowledgeService.layerChunks(KnowledgeService.LAYER_INTERVIEW, 8);
            }
            if (risk.triggered() || containsAny(message, ADVICE_HINTS)) {
                advice = knowledgeService.searchLayer(message, KnowledgeService.LAYER_ADVICE, 2);
            }
        } catch (Exception e) {
            log.warn("知识库检索失败，本次回答不引用知识库，user={}：{}", userId, e.getMessage());
        }
        // 前端"参考来源"只列 E 层论文，知识卡属于内部标准，不作为引用文献展示
        List<ChatSource> sources = knowledgeService.toSources(papers);
        String knowledgePrompt = buildKnowledgePrompt(papers, scales, riskCard, interview, advice, risk);

        // 组装 messages 数组：system 人设 + 历史 + 本条新消息
        // system = 原有人设提示词 + 长期记忆 + RAG 知识段（都拼在 system 里）
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content",
                SYSTEM_PROMPT + memoryService.buildMemoryPrompt(userId) + knowledgePrompt));
        if (history != null) {
            for (ChatMessage m : history) {
                messages.add(Map.of("role", m.role(), "content", m.content()));
            }
        }
        messages.add(Map.of("role", "user", "content", message));
        return new PreparedChat(messages, sources);
    }

    /**
     * 把各层知识块拼成 system prompt 的"专业知识参考"段落。
     * 分组顺序 = 处置优先级：先风险规程，再量表阈值，最后论文证据。
     */
    private String buildKnowledgePrompt(List<KnowledgeService.SearchHit> papers,
            List<KnowledgeService.SearchHit> scales,
            List<KnowledgeService.SearchHit> riskCard,
            List<KnowledgeService.SearchHit> interview,
            List<KnowledgeService.SearchHit> advice,
            CrisisDetector.Signal risk) {
        if (papers.isEmpty() && scales.isEmpty() && riskCard.isEmpty()
                && interview.isEmpty() && advice.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("""

                ## 专业知识参考（来自本项目的分层知识库）
                下面的资料按用途分组，它们是你的专业依据，不是普通参考读物：
                - 【量表与阈值】是判断严重程度的量化标准。引用时**必须连来源一起说明**（哪项研究、哪个人群、哪种参照标准），
                  不许只抛一个分数，也不许把量表分数说成诊断结论；
                - 【原始论文片段】是可核查的证据，**不要编造论文里没有的数字或结论**；
                - 【原始论文片段】后面用括号标了论文类型。标着"AI/算法方法学研究"的，说明研究对象是 AI 或
                  预测模型本身，其中的病例、问答文本是研究材料，**不能当作临床判断依据**，引用它只能说明 AI 的能力边界；
                - 每段前的"来源"就是出处，回答里引用了其中的结论就要写明来源；
                - 与本轮问题确实无关的资料可以不用，但【风险与危机】组只要出现，就必须按它执行。
                """);
        appendGroup(sb, "风险与危机（强制规程）", riskCard);
        appendGroup(sb, "量表与阈值（严重程度判断依据）", scales);
        appendGroup(sb, "评估访谈要点（信息不足时该问什么）", interview);
        appendGroup(sb, "干预建议", advice);
        appendGroup(sb, "原始论文片段", papers, true);
        if (risk.level() == CrisisDetector.Level.HIGH) {
            sb.append("\n\n⚠️ 安全提示：本轮对话检测到明确的风险信号（命中：")
                    .append(String.join("、", risk.matched()))
                    .append("）。请立即按上面的【风险与危机】规程处理：先做一次直接但温和的安全确认，"
                            + "再给出现成的求助渠道并建议就医或联系可信任的人；不要继续常规的信息收集流程，"
                            + "不要淡化处理。若用户是在否认或转述（例如说“我不会那样做”），"
                            + "用一句自然的话确认一下即可，不必反复追问。");
        } else if (risk.level() == CrisisDetector.Level.ELEVATED) {
            sb.append("\n\n提示：本轮对话出现了一些值得留意的表达（命中：")
                    .append(String.join("、", risk.matched()))
                    .append("）。请在合适的时机自然地做一次简短的安全询问，语气保持平稳，不要惊慌或过度渲染。");
        }
        return sb.toString();
    }

    private void appendGroup(StringBuilder sb, String title, List<KnowledgeService.SearchHit> hits) {
        appendGroup(sb, title, hits, false);
    }

    /** annotate=true 时给每条来源补一句"这篇是什么类型的研究"（只用于 E 层论文） */
    private void appendGroup(StringBuilder sb, String title, List<KnowledgeService.SearchHit> hits,
            boolean annotate) {
        if (hits.isEmpty()) {
            return;
        }
        sb.append("\n### 【").append(title).append("】\n");
        for (KnowledgeService.SearchHit hit : hits) {
            sb.append("\n来源：").append(hit.title());
            if (annotate) {
                String note = KnowledgeService.paperNote(hit.title());
                if (!note.isEmpty()) {
                    sb.append("（").append(note).append("）");
                }
            }
            sb.append('\n').append(hit.text()).append('\n');
        }
    }

    /** 最近几条用户消息 + 本轮消息，拼成风险扫描的文本（只看最近 4 条，避免旧话题反复触发） */
    private String recentUserText(List<ChatMessage> history, String message) {
        StringBuilder sb = new StringBuilder();
        if (history != null) {
            int taken = 0;
            for (int i = history.size() - 1; i >= 0 && taken < 4; i--) {
                ChatMessage m = history.get(i);
                if ("user".equals(m.role())) {
                    sb.insert(0, m.content() + "\n");
                    taken++;
                }
            }
        }
        return sb.append(message).toString();
    }

    /** 对话刚开始（用户发言不超过 2 条）时注入 C 层访谈要点 */
    private boolean isEarlyStage(List<ChatMessage> history) {
        if (history == null) {
            return true;
        }
        return history.stream().filter(m -> "user".equals(m.role())).count() <= 2;
    }

    private boolean containsAny(String text, List<String> hints) {
        if (text == null) {
            return false;
        }
        for (String hint : hints) {
            if (text.contains(hint)) {
                return true;
            }
        }
        return false;
    }
}

