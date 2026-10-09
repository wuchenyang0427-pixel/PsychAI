package com.psych.controller;

import com.psych.dto.ApiResponse;
import com.psych.dto.KnowledgeArticle;
import com.psych.dto.KnowledgeSaveRequest;
import com.psych.dto.UserInfo;
import com.psych.exception.BizException;
import com.psych.service.AuthService;
import com.psych.service.DocumentTextExtractor;
import com.psych.service.KnowledgeService;
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
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * 知识文章管理接口（RAG 知识库维护）：全部仅 admin 可用。
 * 新增/编辑会触发切块 + Ollama 向量化，Ollama 不在线时操作失败并提示。
 */
@RestController
@RequestMapping("/api/knowledge/admin")
public class KnowledgeController {

    private final KnowledgeService knowledgeService;
    private final AuthService authService;
    private final DocumentTextExtractor textExtractor;

    /** 文件入库后正文长度上限（约 300+ 知识块，可容纳整篇论文；再长 CPU 向量化会很慢） */
    private static final int MAX_FILE_TEXT_LENGTH = 100000;

    public KnowledgeController(KnowledgeService knowledgeService, AuthService authService,
            DocumentTextExtractor textExtractor) {
        this.knowledgeService = knowledgeService;
        this.authService = authService;
        this.textExtractor = textExtractor;
    }

    /** 文章列表，可按标题/分类关键词过滤 */
    @GetMapping
    public ApiResponse<List<KnowledgeArticle>> list(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(value = "keyword", required = false) String keyword) {
        requireAdmin(authService.currentUser(authHeader));
        return ApiResponse.success(knowledgeService.adminList(keyword));
    }

    /** 新增文章（同步切块向量化） */
    @PostMapping
    public ApiResponse<KnowledgeArticle> create(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @Valid @RequestBody KnowledgeSaveRequest body) {
        requireAdmin(authService.currentUser(authHeader));
        return ApiResponse.success(
                knowledgeService.create(body.title(), body.category(), body.content()));
    }

    /**
     * 上传文件新增文章：PDF / Word(.docx,.doc) / TXT / Markdown。
     * 流程：鉴权 → 校验类型 → 抽取纯文本 → 复用与手动输入相同的切块向量化入库。
     * 论文等长文档向量化耗时较长，前端需保持 loading。
     */
    @PostMapping("/upload")
    public ApiResponse<KnowledgeArticle> upload(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam("file") MultipartFile file,
            @RequestParam("category") String category,
            @RequestParam(value = "title", required = false) String title) {
        requireAdmin(authService.currentUser(authHeader));

        if (file == null || file.isEmpty()) {
            throw new BizException("请选择要上传的文件");
        }
        String originalName = file.getOriginalFilename();
        if (category == null || category.isBlank()) {
            throw new BizException("请选择分类");
        }
        if (!textExtractor.supports(originalName)) {
            throw new BizException("不支持的文件类型，仅支持 PDF / Word(.docx,.doc) / TXT / Markdown");
        }

        String content;
        try {
            content = textExtractor.extract(originalName, file.getBytes());
        } catch (IOException e) {
            throw new BizException("读取上传文件失败，请重试");
        }
        if (content.isBlank()) {
            throw new BizException("未从文件中解析到文字内容（可能是扫描件/纯图片 PDF，请提供可复制文字的版本）");
        }
        if (content.length() > MAX_FILE_TEXT_LENGTH) {
            throw new BizException(
                    "解析出的正文共 " + content.length() + " 字，超过 " + MAX_FILE_TEXT_LENGTH
                            + " 字上限，请拆分文档或只上传核心章节");
        }

        // 标题留空时用文件名（去掉扩展名）兜底，与心声墙"匿名"兜底同理
        String finalTitle = (title == null || title.isBlank())
                ? stripExtension(originalName)
                : title.trim();
        if (finalTitle.length() > 100) {
            finalTitle = finalTitle.substring(0, 100);
        }
        return ApiResponse.success(knowledgeService.create(finalTitle, category.trim(), content));
    }

    /** 去掉文件名末尾的扩展名：xxx.pdf → xxx */
    private String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    /** 编辑文章（重切块、重向量化） */
    @PutMapping("/{id}")
    public ApiResponse<Void> update(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable String id,
            @Valid @RequestBody KnowledgeSaveRequest body) {
        requireAdmin(authService.currentUser(authHeader));
        try {
            knowledgeService.update(id, body.title(), body.category(), body.content());
        } catch (IllegalArgumentException e) {
            throw new BizException(e.getMessage());
        }
        return ApiResponse.success(null);
    }

    /** 删除文章（连同全部知识块和向量索引） */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable String id) {
        requireAdmin(authService.currentUser(authHeader));
        try {
            knowledgeService.delete(id);
        } catch (IllegalArgumentException e) {
            throw new BizException(e.getMessage());
        }
        return ApiResponse.success(null);
    }

    private void requireAdmin(UserInfo me) {
        if (!"admin".equals(me.role())) {
            throw new BizException("仅管理员可进行此操作");
        }
    }
}
