package com.psych.service;

import com.psych.exception.BizException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 文档文本抽取器：把上传的各类文件"读成纯文字"。
 *
 * 类比：向量化只认得文字，所以这一步像"把 PDF/Word 里的纸和排版去掉，
 * 只留下文字内容"，之后再交给现有的切块 + bge-m3 向量化流程。
 *
 * 支持：PDF（论文常用）、Word 新旧两种格式（.docx / .doc）、纯文本（.txt / .md）。
 */
@Service
public class DocumentTextExtractor {

    /** 支持的扩展名（小写、不带点），控制器和前端共用这份口径 */
    public static final String[] SUPPORTED_EXTENSIONS = {"pdf", "docx", "doc", "txt", "md"};

    /**
     * 按文件扩展名分派到对应的解析器。
     *
     * @param filename  原始文件名（用于识别扩展名）
     * @param fileBytes 文件完整字节
     * @return 抽取并清洗后的纯文本
     */
    public String extract(String filename, byte[] fileBytes) {
        String ext = extensionOf(filename);
        try {
            String text = switch (ext) {
                case "pdf" -> extractPdf(fileBytes);
                case "docx" -> extractDocx(fileBytes);
                case "doc" -> extractDoc(fileBytes);
                case "txt", "md" -> extractPlainText(fileBytes);
                default -> throw new BizException(
                        "不支持的文件类型：." + ext + "，仅支持 PDF / Word / TXT / Markdown");
            };
            // PDF/Word 抽出的文字常带多余空白和连续空行，统一清洗，切块效果更好
            return normalize(text);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            // 文件损坏、加密 PDF、假扩展名等都会在这里暴露
            throw new BizException("文件解析失败（." + ext + "）：文件可能已损坏或已加密，请另存为未加密的 PDF/Word 后重试");
        }
    }

    public boolean supports(String filename) {
        String ext = extensionOf(filename);
        for (String allowed : SUPPORTED_EXTENSIONS) {
            if (allowed.equals(ext)) {
                return true;
            }
        }
        return false;
    }

    // ==================== 各格式解析 ====================

    /** PDFBox 3.x：Loader.loadPDF 直接吃字节数组，无需落盘 */
    private String extractPdf(byte[] bytes) throws Exception {
        try (var doc = Loader.loadPDF(bytes)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    /** .docx：OOXML 格式（本质是 zip+xml），POI 的 XWPF 系列解析 */
    private String extractDocx(byte[] bytes) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes));
             XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
            return extractor.getText();
        }
    }

    /** .doc：老版二进制格式，需要 poi-scratchpad 的 HWPF 系列 */
    private String extractDoc(byte[] bytes) throws Exception {
        try (HWPFDocument doc = new HWPFDocument(new ByteArrayInputStream(bytes));
             WordExtractor extractor = new WordExtractor(doc)) {
            return extractor.getText();
        }
    }

    /**
     * 纯文本：Windows 中文环境常见 GBK 编码的 txt，
     * 先按 UTF-8 解，若出现大量替换字符（�）就换 GBK，避免乱码入库。
     */
    private String extractPlainText(byte[] bytes) {
        String utf8 = new String(bytes, StandardCharsets.UTF_8);
        long replacementCount = utf8.chars().filter(c -> c == '\uFFFD').count();
        if (replacementCount > 0 && Charset.isSupported("GBK")) {
            String gbk = new String(bytes, Charset.forName("GBK"));
            // GBK 解码后替换字符更少，说明原文大概率是 GBK
            long gbkBad = gbk.chars().filter(c -> c == '\uFFFD').count();
            if (gbkBad < replacementCount) {
                return gbk;
            }
        }
        return utf8;
    }

    // ==================== 工具方法 ====================

    private String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot + 1).toLowerCase() : "";
    }

    /** 文本清洗：去行尾空白、把 3 个以上连续换行压成 2 个（保留段落分隔） */
    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        String[] lines = text.split("\\r?\\n", -1);
        StringBuilder sb = new StringBuilder(text.length());
        int blankRun = 0;
        for (String rawLine : lines) {
            String line = rawLine.stripTrailing();
            if (line.isBlank()) {
                if (++blankRun <= 1) {
                    sb.append('\n');
                }
            } else {
                blankRun = 0;
                sb.append(line).append('\n');
            }
        }
        return sb.toString().trim();
    }
}
