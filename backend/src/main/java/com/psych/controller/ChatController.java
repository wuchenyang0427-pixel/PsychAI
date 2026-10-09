package com.psych.controller;

import com.psych.dto.ApiResponse;
import com.psych.dto.AssessRequest;
import com.psych.dto.ChatRequest;
import com.psych.dto.ReadinessData;
import com.psych.dto.UserInfo;
import com.psych.service.AuthService;
import com.psych.service.DeepSeekService;
import com.psych.service.ReportService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AI 对话接口：必须登录才能用（演示 token 认证的实际用法）。
 * 聊天回答走 SSE 流式输出：返回 SseEmitter 后 Spring 自动把响应头设为 text/event-stream。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final DeepSeekService deepSeekService;
    private final AuthService authService;
    private final ReportService reportService;

    public ChatController(DeepSeekService deepSeekService, AuthService authService,
            ReportService reportService) {
        this.deepSeekService = deepSeekService;
        this.authService = authService;
        this.reportService = reportService;
    }

    @PostMapping
    public SseEmitter chat(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @Valid @RequestBody ChatRequest chatRequest) {

        // 第一步：校验 token，拿到当前用户（记忆按 userId 隔离，userId 只来自 token）
        // 鉴权失败抛 BizException，此时 SSE 还没建立，照常走 GlobalExceptionHandler 的 JSON 错误
        UserInfo me = authService.currentUser(authHeader);

        // 第二步：创建 SSE 发射器并立刻返回（连接被挂起，响应头先发给前端）；
        // 模型调用在后台线程里做，增量通过 emitter 逐段推送（3 分钟无结果则由超时回调强制收尾）
        SseEmitter emitter = new SseEmitter(180_000L);
        deepSeekService.chatStream(me.userId(), chatRequest.message(), chatRequest.history(),
                emitter);
        return emitter;
    }

    /**
     * AI 评分：评估当前对话信息完整度，决定"生成报告"按钮是否解锁。
     */
    @PostMapping("/assess")
    public ApiResponse<ReadinessData> assess(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @Valid @RequestBody AssessRequest assessRequest) {
        authService.currentUser(authHeader);
        return ApiResponse.success(reportService.assess(assessRequest.history()));
    }
}
