package com.psych.controller;

import com.psych.dto.ApiResponse;
import com.psych.dto.StatsOverview;
import com.psych.dto.UserInfo;
import com.psych.exception.BizException;
import com.psych.service.AuthService;
import com.psych.service.StatsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据分析看板接口（仅管理员）。
 * 统计口径全部由后端从 Redis 真实聚合，前端只负责展示。
 */
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final StatsService statsService;
    private final AuthService authService;

    public StatsController(StatsService statsService, AuthService authService) {
        this.statsService = statsService;
        this.authService = authService;
    }

    @GetMapping("/overview")
    public ApiResponse<StatsOverview> overview(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        UserInfo me = authService.currentUser(authHeader);
        if (!"admin".equals(me.role())) {
            throw new BizException("仅管理员可查看数据分析");
        }
        return ApiResponse.success(statsService.overview());
    }
}
