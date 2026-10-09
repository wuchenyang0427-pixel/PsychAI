package com.psych.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.psych.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 本地 Ollama 嵌入服务：把一段文字变成一串数字（向量）。
 *
 * 原理类比：嵌入模型就像一台"语义翻译机"——意思相近的两句话，
 * 翻译出来的数字串在空间里的方向也相近。之后只要算"方向有多接近"
 * （余弦相似度），就能知道两段文字是不是在讲同一件事。
 *
 * 与 DeepSeekService 一样零框架：直接 POST Ollama 的 /api/embed。
 */
@Service
public class OllamaEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(OllamaEmbeddingService.class);

    /** 每批送入 Ollama 的文本块数量，防止论文等长文档一次性嵌入超时 */
    private static final int EMBED_BATCH_SIZE = 16;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final ObjectMapper objectMapper;

    @Value("${app.ollama.base-url}")
    private String baseUrl;

    @Value("${app.ollama.embedding-model}")
    private String model;

    public OllamaEmbeddingService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 批量把多段文本变成向量。
     * 文件入库（如整篇论文）一次可能产生上百个块，单请求塞太多会让 Ollama
     * CPU 推理超时，所以这里按固定批次分批请求再拼结果。
     *
     * @param texts 待嵌入的文本列表
     * @return 与入参顺序一一对应的向量列表；Ollama 不可用时抛业务异常
     */
    public List<double[]> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        List<double[]> all = new ArrayList<>(texts.size());
        int from = 0;
        while (from < texts.size()) {
            int to = Math.min(from + EMBED_BATCH_SIZE, texts.size());
            all.addAll(embedOneBatch(texts.subList(from, to)));
            from = to;
        }
        return all;
    }

    /** 单批嵌入：一次请求最多 EMBED_BATCH_SIZE 段 */
    private List<double[]> embedOneBatch(List<String> texts) {
        try {
            Map<String, Object> body = Map.of("model", model, "input", texts);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/embed"))
                    // bge-m3 本地 CPU 跑，一批文本嵌入可能要几十秒
                    .timeout(Duration.ofSeconds(90))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Ollama 嵌入失败，HTTP {}，body={}", response.statusCode(),
                        abbreviate(response.body()));
                throw new BizException("本地嵌入服务返回异常（HTTP " + response.statusCode() + "）");
            }

            JsonNode embeddings = objectMapper.readTree(response.body()).path("embeddings");
            if (!embeddings.isArray() || embeddings.size() != texts.size()) {
                throw new BizException("本地嵌入服务返回格式异常");
            }
            List<double[]> vectors = new ArrayList<>(embeddings.size());
            for (JsonNode vec : embeddings) {
                double[] v = new double[vec.size()];
                for (int i = 0; i < vec.size(); i++) {
                    v[i] = vec.get(i).asDouble();
                }
                vectors.add(v);
            }
            return vectors;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            // 最常见的原因：Ollama 没启动，或还没 pull bge-m3
            log.warn("调用 Ollama 嵌入服务失败", e);
            throw new BizException("本地嵌入服务不可用，请确认 Ollama 已启动且已安装 bge-m3 模型");
        }
    }

    /**
     * 单段文本的便捷入口（检索查询用）。
     *
     * 这里刻意不加任何"指令前缀"。旧实现给每个查询硬拼了
     * "为这个句子生成表示以用于检索相关文章："，那是 bge-large-zh 的用法，
     * bge-m3 并不需要；而且论文块入库时并不加前缀，只有查询端加，
     * 造成查询和文档编码方式不对称。实测去掉前缀后片段 Recall@1 从 37.0% 升到 44.4%，
     * 而且英文问题不会再被拼上一句中文前缀。
     */
    public double[] embedOne(String text) {
        return embed(List.of(text)).get(0);
    }

    private String abbreviate(String s) {
        return s == null ? "" : s.substring(0, Math.min(s.length(), 300));
    }
}

