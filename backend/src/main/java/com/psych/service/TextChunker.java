package com.psych.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 论文正文清洗 + 语义分块（RAG 入库的第一步）。
 *
 * 为什么要清洗：从 PMC / 期刊页面抽出来的全文里，通常混着三类噪声——
 *   1. 前言区的作者单位和投稿信息（"Find articles by …"、"Department of …"）；
 *   2. 后置区的参考文献列表、脚注、致谢、数据可用性声明；
 *   3. 页面的样板文字（"[ DOI ] [ PubMed ] [ Google Scholar ]"、"courtesy of …"）。
 * 这些内容被切成块后会被向量检索当成"相关内容"命中，把真正的正文挤出前几名。
 *
 * 为什么要重新分块：旧实现按空行切段，而论文全文几乎不使用空行，
 * 整篇三万字的论文被当成一个段落 → 再用 300 字滑窗硬切 → 72% 的块从单词中间断开
 * （例如 "rceived burdensomeness"、"om sample of participants"）。
 * 现在的做法是：先按"标题 → 句子"两级拆成最小语义单元，再按目标长度打包，
 * 每个块都以它所在的章节标题开头，块与块之间用上一块的最后一句话衔接。
 */
public final class TextChunker {

    private TextChunker() {
    }

    /** 后置材料的起始标记：必须独占一行（可带 Markdown # 号或 ** 加粗），避免误伤正文里的 "see Supplementary" */
    private static final Pattern BACK_MATTER = Pattern.compile(
            "(?m)^[ \\t]*(?:#{1,6}[ \\t]*)?(?:\\*\\*[ \\t]*)?"
                    + "(?:References|Reference List|Bibliography|Literature Cited|Footnotes|Associated Data|"
                    + "Supplementary Materials?|Supplemental Material|Supplementary information|"
                    + "Data Availability Statement|Data availability statement|Data Sharing Statement|"
                    + "Author Contributions?|Author'?s? notes?|Acknowledg(?:e)?ments?|"
                    + "Conflict of Interest(?: Statement)?|Competing [Ii]nterests?|"
                    + "Publisher'?s [Nn]ote|Ethics Statement|Funding(?: Statement)?|"
                    + "Contributor Information|Reporting Summary|Online content|Abbreviations|"
                    + "Research Data)"
                    + "\\b[ \\t]*:?[ \\t]*(?:\\*\\*)?[ \\t]*$");

    /** 正文起点：摘要/引言这类"内容标题"，用来把前言区的作者信息整段丢掉 */
    private static final Pattern BODY_START = Pattern.compile(
            "(?m)^#{1,3}[ \\t]*(?:Abstract|Summary|Significance|Highlights?|Main|"
                    + "Background|Introduction|Objectives?|Methods?|Materials and Methods|Results?|"
                    + "Conclusions?|Findings|Discussion)\\b");

    private static final Pattern PMC_LINE = Pattern.compile(
            "(?m)^[ \\t]*\\[ ?(?:DOI|PubMed|PMC free article|Google Scholar|PMC) ?\\][ \\t]*$");
    private static final Pattern COURTESY = Pattern.compile(
            "Articles from .*? are provided here courtesy of .*");
    private static final Pattern HTML_TAG = Pattern.compile(
            "</?(?:p|div|span|br|em|strong|i|b|sub|sup|table|tr|td|th)[^>]*>");
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})[ \\t]+(.*)$");
    /** 折句：中文句末标点后直接切；英文句点后必须跟空白且下一个字符像新句首 */
    private static final Pattern SENT_SPLIT = Pattern.compile(
            "(?<=[\u3002\uff01\uff1f\uff1b!?;])\\s*|(?<=\\.)\\s+(?=[A-Z0-9\u201c\u2018\"(\\[])");

    private static final String[][] MOJIBAKE = {
            {"\u00c3\u00a2\u20ac\u2122", "'"}, {"\u00c3\u00a2\u20ac\u0153", "\""},
            {"\u00c3\u00a2\u20ac\u009d", "\""}, {"\u00c3\u00a2\u20ac\u201c", "\u2014"},
            {"\u00c3\u00a2\u20ac\u201d", "\u2014"}, {"\u00c3\u00a2\u20ac\u02dc", "'"},
            {"\u00c2\u00a0", " "}, {"\u00c2", ""}, {"\u00a0", " "},
    };

    /** 清洗结果，附带被丢弃的字符数（用于日志和前后对比） */
    public record Cleaned(String text, int frontDropped, int backDropped) {
    }

    /** 一个最小语义单元：一句话，或一行标题 */
    private record Unit(String heading, String text, boolean headingLine) {
    }

    /**
     * 清洗整篇论文正文。
     *
     * @param raw 抽取出来的原始文本（Markdown 或纯文本）
     * @return 清洗后的正文，结构为：一级标题 + 期刊信息一行 + 正文
     */
    public static Cleaned clean(String raw) {
        String t = raw == null ? "" : raw.replace("\r\n", "\n").replace("\r", "\n");
        for (String[] pair : MOJIBAKE) {
            t = t.replace(pair[0], pair[1]);
        }
        t = HTML_TAG.matcher(t).replaceAll("");

        String[] lines = t.split("\n", -1);
        String title = lines.length > 0 ? lines[0].strip() : "";
        String meta = "";
        for (int i = 1; i < Math.min(lines.length, 12); i++) {
            String ln = lines[i];
            if (ln.contains("DOI:") || ln.matches(".*\\(\\d{4}\\).*")) {
                meta = ln.strip();
                break;
            }
        }

        // 1. 正文起点：找到第一个内容标题，把它之前（标题/期刊信息之外）的内容整段丢掉
        Matcher body = BODY_START.matcher(t);
        boolean hasBody = body.find();
        int bodyStart = hasBody ? body.start() : -1;
        if (bodyStart == 0) {
            title = ""; // 首行本身就是正文起点（没有独立标题行），不要重复
        }

        // 2. 后置材料：只在文档后半篇里找起点，防止正文里一句 "see Supplementary" 把文章截断
        String tail = hasBody ? t.substring(bodyStart) : t;
        int cut = tail.length();
        Matcher back = BACK_MATTER.matcher(tail);
        while (back.find()) {
            if (back.start() >= 0.40 * tail.length()) {
                cut = back.start();
                break;
            }
        }
        int backDropped = tail.length() - cut;
        tail = tail.substring(0, cut);

        // 3. 收尾：清掉页面样板、压缩空白
        tail = COURTESY.matcher(tail).replaceAll("");
        tail = PMC_LINE.matcher(tail).replaceAll("");
        tail = tail.replaceAll("[ \\t]+\\n", "\n");
        tail = tail.replaceAll("\\n{3,}", "\n\n");
        tail = tail.replaceAll("[ \\t]{2,}", " ");

        StringBuilder sb = new StringBuilder();
        if (!title.isEmpty()) {
            sb.append(title).append("\n\n");
        }
        if (!meta.isEmpty()) {
            sb.append(meta).append("\n\n");
        }
        sb.append(tail.strip());
        int frontDropped = Math.max(bodyStart, 0);
        return new Cleaned(sb.toString().strip(), frontDropped, backDropped);
    }

    /** 把清洗后的正文拆成"标题 + 句子"两级单元 */
    static List<Unit> units(String text) {
        List<Unit> units = new ArrayList<>();
        String heading = "";
        for (String rawLine : text.split("\n", -1)) {
            String line = rawLine.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (HEADING.matcher(line).matches()) {
                heading = line;
                units.add(new Unit(heading, line, true));
                continue;
            }
            for (String s : SENT_SPLIT.split(line)) {
                String sentence = s.strip();
                if (!sentence.isEmpty()) {
                    units.add(new Unit(heading, sentence, false));
                }
            }
        }
        return units;
    }

    /**
     * 按目标长度把句子打包成块：同一章节的句子尽量放一起，
     * 块首带上章节标题，跨块时用上一块的末句做重叠。
     *
     * @param text   清洗后的正文
     * @param target 目标块长度（字符）
     * @param overlapMaxSentence 用作重叠的末句最大长度，超过就不重叠
     */
    public static List<String> split(String text, int target, int overlapMaxSentence) {
        List<String> chunks = new ArrayList<>();
        List<String> buf = new ArrayList<>();
        String curHeading = null;
        for (Unit u : units(text)) {
            if (u.headingLine()) {
                flush(chunks, buf);
                curHeading = u.text();
                buf.add(u.text());
                continue;
            }
            if (!Objects.equals(u.heading(), curHeading)) {
                flush(chunks, buf);
                curHeading = u.heading();
                if (!curHeading.isEmpty()) {
                    buf.add(curHeading);
                }
            }
            if (u.text().length() > target) {
                // 单个句子就超长（表格行、数字串）：先结块，再对这个句子硬切
                flush(chunks, buf);
                List<String> prefix = new ArrayList<>();
                if (!curHeading.isEmpty()) {
                    prefix.add(curHeading);
                }
                for (String piece : hardSplit(u.text(), target)) {
                    if (!curHeading.isEmpty()) {
                        chunks.add(curHeading + "\n" + piece);
                    } else {
                        chunks.add(piece);
                    }
                }
                buf.addAll(prefix);
                continue;
            }
            int curLen = 0;
            for (String s : buf) {
                curLen += s.length() + 1;
            }
            if (!buf.isEmpty() && curLen + u.text().length() > target) {
                String prev = chunks.isEmpty() ? "" : chunks.get(chunks.size() - 1);
                flush(chunks, buf);
                String tailSentence = tailSentence(prev, overlapMaxSentence);
                if (!curHeading.isEmpty()) {
                    buf.add(curHeading);
                }
                if (!tailSentence.isEmpty()) {
                    buf.add(tailSentence);
                }
            }
            buf.add(u.text());
        }
        flush(chunks, buf);
        List<String> out = new ArrayList<>();
        for (String c : chunks) {
            String s = c.strip();
            if (s.length() > 40) {
                out.add(s);
            }
        }
        return out;
    }

    private static void flush(List<String> chunks, List<String> buf) {
        if (!buf.isEmpty()) {
            chunks.add(String.join("\n", buf).strip());
            buf.clear();
        }
    }

    /** 超长单元按字符硬切，尽量落在空格处，避免切断英文单词 */
    static List<String> hardSplit(String s, int target) {
        List<String> out = new ArrayList<>();
        int size = Math.max(target, 1);
        int i = 0;
        while (i < s.length()) {
            int j = Math.min(i + size, s.length());
            if (j < s.length()) {
                int sp = s.lastIndexOf(' ', j);
                if (sp > i + size / 2) {
                    j = sp;
                }
            }
            String piece = s.substring(i, j).strip();
            if (!piece.isEmpty()) {
                out.add(piece);
            }
            i = j;
        }
        return out;
    }

    /** 取上一块的最后一句做重叠；块以标题开头时跳过标题行 */
    static String tailSentence(String chunk, int limit) {
        String body = chunk;
        if (chunk.startsWith("#")) {
            int nl = chunk.indexOf('\n');
            body = nl < 0 ? "" : chunk.substring(nl + 1);
        }
        String flat = body.replace("\n", " ").strip();
        if (flat.isEmpty()) {
            return "";
        }
        String[] parts = SENT_SPLIT.split(flat);
        for (int i = parts.length - 1; i >= 0; i--) {
            String last = parts[i].strip();
            if (!last.isEmpty()) {
                return last.length() <= limit ? last : "";
            }
        }
        return "";
    }

}

