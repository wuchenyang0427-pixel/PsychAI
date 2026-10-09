package com.psych.controller;

import com.psych.dto.ApiResponse;
import com.psych.dto.ConsultUserRecord;
import com.psych.dto.ReportData;
import com.psych.dto.UserInfo;
import com.psych.exception.BizException;
import com.psych.service.AuthService;
import com.psych.service.ConsultRecordService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 咨询记录接口（后台）：人员列表 + 人员对应的心理调查报告。
 * 全部接口仅管理员可用，人员ID只作为查询条件，不参与鉴权（鉴权只认 token）。
 */
@RestController
@RequestMapping("/api/records")
public class RecordController {

    private final ConsultRecordService recordService;
    private final AuthService authService;

    public RecordController(ConsultRecordService recordService, AuthService authService) {
        this.recordService = recordService;
        this.authService = authService;
    }

    /** 咨询人员列表：可按人员ID模糊过滤，重度人员由后端排在最前 */
    @GetMapping
    public ApiResponse<List<ConsultUserRecord>> list(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(value = "userId", required = false) String userId) {
        requireAdmin(authService.currentUser(authHeader));
        return ApiResponse.success(recordService.listUsers(userId));
    }

    /** 查看某个人员的全部心理调查报告（新报告在前） */
    @GetMapping("/{userId}")
    public ApiResponse<List<ReportData>> reports(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable String userId) {
        requireAdmin(authService.currentUser(authHeader));
        List<ReportData> reports = recordService.reportsOfUser(userId);
        if (reports.isEmpty()) {
            throw new BizException("该人员不存在或暂无心理调查报告");
        }
        return ApiResponse.success(reports);
    }

    /** 角色守门：咨询记录只允许管理员查看 */
    private void requireAdmin(UserInfo me) {
        if (!"admin".equals(me.role())) {
            throw new BizException("仅管理员可查看咨询记录");
        }
    }
}
