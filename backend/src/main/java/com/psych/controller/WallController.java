package com.psych.controller;

import com.psych.dto.ApiResponse;
import com.psych.dto.UserInfo;
import com.psych.dto.WallMessage;
import com.psych.dto.WallPublishRequest;
import com.psych.dto.WallSaveRequest;
import com.psych.exception.BizException;
import com.psych.service.AuthService;
import com.psych.service.WallService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 故事集（心声墙）接口：全部需要登录。
 * userId 只从 token 解析（点赞防重按用户隔离），心声 ID 走路径参数无妨。
 */
@RestController
@RequestMapping("/api/wall")
public class WallController {

    private final WallService wallService;
    private final AuthService authService;

    public WallController(WallService wallService, AuthService authService) {
        this.wallService = wallService;
        this.authService = authService;
    }

    /** 拉取全部心声（含当前用户是否已点赞） */
    @GetMapping
    public ApiResponse<List<WallMessage>> list(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        UserInfo me = authService.currentUser(authHeader);
        return ApiResponse.success(wallService.list(me.userId()));
    }

    /** 为一条心声点赞（重复点击幂等，不重复计数） */
    @PostMapping("/like/{messageId}")
    public ApiResponse<Long> like(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable String messageId) {
        UserInfo me = authService.currentUser(authHeader);
        try {
            return ApiResponse.success(wallService.like(me.userId(), messageId));
        } catch (IllegalArgumentException e) {
            throw new BizException(e.getMessage());
        }
    }

    /** 用户发布心声（30 秒防刷冷却，数据真实写入 Redis） */
    @PostMapping
    public ApiResponse<WallMessage> publish(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @Valid @RequestBody WallPublishRequest body) {
        UserInfo me = authService.currentUser(authHeader);
        return ApiResponse.success(
                wallService.publish(me.userId(), body.username(), body.content()));
    }

    /** 用户删除自己发布的心声（authorId 校验在 service 完成） */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteOwn(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable String id) {
        UserInfo me = authService.currentUser(authHeader);
        wallService.deleteByAuthor(me.userId(), id);
        return ApiResponse.success(null);
    }

    // ==================== 管理端：故事集 CRUD（仅 admin） ====================

    /** 管理端列表，可按关键词过滤（匹配用户名或内容） */
    @GetMapping("/admin")
    public ApiResponse<List<WallMessage>> adminList(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(value = "keyword", required = false) String keyword) {
        requireAdmin(authService.currentUser(authHeader));
        return ApiResponse.success(wallService.adminList(keyword));
    }

    /** 新增心声 */
    @PostMapping("/admin")
    public ApiResponse<WallMessage> create(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @Valid @RequestBody WallSaveRequest body) {
        requireAdmin(authService.currentUser(authHeader));
        return ApiResponse.success(wallService.create(body.username(), body.content()));
    }

    /** 编辑心声（点赞数不变） */
    @PutMapping("/admin/{id}")
    public ApiResponse<Void> update(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable String id,
            @Valid @RequestBody WallSaveRequest body) {
        requireAdmin(authService.currentUser(authHeader));
        try {
            wallService.update(id, body.username(), body.content());
        } catch (IllegalArgumentException e) {
            throw new BizException(e.getMessage());
        }
        return ApiResponse.success(null);
    }

    /** 删除心声（同时清理所有用户的点赞记录） */
    @DeleteMapping("/admin/{id}")
    public ApiResponse<Void> delete(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable String id) {
        requireAdmin(authService.currentUser(authHeader));
        try {
            wallService.delete(id);
        } catch (IllegalArgumentException e) {
            throw new BizException(e.getMessage());
        }
        return ApiResponse.success(null);
    }

    /** 角色守门：故事集管理接口只允许管理员调用 */
    private void requireAdmin(UserInfo me) {
        if (!"admin".equals(me.role())) {
            throw new BizException("仅管理员可进行此操作");
        }
    }
}
