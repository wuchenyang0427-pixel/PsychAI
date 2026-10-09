package com.psych.controller;

import com.psych.dto.ApiResponse;
import com.psych.dto.MemoryCaptureRequest;
import com.psych.dto.MemoryCard;
import com.psych.dto.MemoryView;
import com.psych.dto.UserInfo;
import com.psych.service.AuthService;
import com.psych.service.MemoryService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AI 记忆接口：查看 / 清空 / 手动提取。
 *
 * 安全红线：userId 一律从 token 里解析（authService.currentUser），
 * 绝不能做成 URL 参数——否则别人改个手机号就能读到你的心理档案。
 */
@RestController
@RequestMapping("/api/memory")
public class MemoryController {

    private final MemoryService memoryService;
    private final AuthService authService;

    public MemoryController(MemoryService memoryService, AuthService authService) {
        this.memoryService = memoryService;
        this.authService = authService;
    }

    /** 查看 AI 记住了什么：档案 + 最近记忆卡 + 未完结话题 */
    @GetMapping
    public ApiResponse<MemoryView> overview(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        UserInfo me = authService.currentUser(authHeader);
        return ApiResponse.success(new MemoryView(
                memoryService.readProfile(me.userId()),
                memoryService.recentCards(me.userId(), 5),
                memoryService.readOpenThreads(me.userId())));
    }

    /** 手动触发记忆提取（正常流程是报告生成后自动提取，这里留给调试/演示） */
    @PostMapping("/capture")
    public ApiResponse<MemoryCard> capture(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @Valid @RequestBody MemoryCaptureRequest request) {
        UserInfo me = authService.currentUser(authHeader);
        return ApiResponse.success(memoryService.capture(me.userId(), request.history()));
    }

    /** 清空当前用户全部记忆（前端有二次确认） */
    @DeleteMapping
    public ApiResponse<Void> clearAll(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        UserInfo me = authService.currentUser(authHeader);
        memoryService.clearAll(me.userId());
        return ApiResponse.success(null);
    }
}
