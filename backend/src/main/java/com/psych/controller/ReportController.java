package com.psych.controller;

import com.psych.dto.ApiResponse;
import com.psych.dto.GenerateReportRequest;
import com.psych.dto.ReportData;
import com.psych.dto.UserInfo;
import com.psych.service.AuthService;
import com.psych.service.ReportService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 心理分析报告接口：全部需要登录，报告按用户隔离。
 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportService reportService;
    private final AuthService authService;

    public ReportController(ReportService reportService, AuthService authService) {
        this.reportService = reportService;
        this.authService = authService;
    }

    /** 基于对话历史生成一份报告（调模型，较慢，属正常现象） */
    @PostMapping("/generate")
    public ApiResponse<ReportData> generate(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @Valid @RequestBody GenerateReportRequest request) {
        UserInfo me = authService.currentUser(authHeader);
        return ApiResponse.success(reportService.generateReport(me.userId(), request.history()));
    }

    /** 我的报告列表 */
    @GetMapping
    public ApiResponse<List<ReportData>> list(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        UserInfo me = authService.currentUser(authHeader);
        return ApiResponse.success(reportService.listReports(me.userId()));
    }

    /** 单份报告详情 */
    @GetMapping("/{reportId}")
    public ApiResponse<ReportData> detail(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable String reportId) {
        UserInfo me = authService.currentUser(authHeader);
        return ApiResponse.success(reportService.getReport(me.userId(), reportId));
    }
}
