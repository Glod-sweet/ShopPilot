package com.shoppilot.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.config.EmbeddingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 向量化服务：把文本转成向量，并提供余弦相似度计算。
 * <p>
 * 走 OpenAI 兼容协议（{@code POST /embeddings}），换供应商只改配置。
 * <p>
 * 三个设计决定：
 * <ol>
 *   <li><b>query 向量带缓存</b>。同一个用户的追问往往重复同一句话（「那便宜点的呢」），
 *       缓存命中能省一次 200ms 的网络往返。用 LinkedHashMap 做 LRU，上限 512 条。</li>
 *   <li><b>失败返回空数组而不是抛异常</b>。向量化挂了不应该让整个检索 500，
 *       调用方拿到空数组后自动降级成关键词打分。</li>
 *   <li><b>余弦用 float 累加</b>。1536 维 × 200 个候选 = 30 万次乘加，
 *       用 double 没必要，float 精度足够且快一倍。</li>
 * </ol>
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final EmbeddingProperties props;
    private final WebClient webClient;
    private final ObjectMapper mapper = new ObjectMapper();

    /** query 向量 LRU 缓存。key 是原文，value 是向量 */
    private final Map<String, float[]> queryCache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, float[]> eldest) {
            return size() > 512;
        }
    };

    /** 记录最近一次失败时间，避免接口挂了还每轮都去试（熔断 60 秒） */
    private volatile long circuitOpenUntil = 0L;

    public EmbeddingService(EmbeddingProperties props, WebClient.Builder builder) {
        this.props = props;
        this.webClient = builder
                .baseUrl(props.baseUrl() == null ? "http://localhost" : props.baseUrl())
                .build();
    }

    /** 是否可用：配了 key 且熔断器没开 */
    public boolean available() {
        return props.enabled() && System.currentTimeMillis() > circuitOpenUntil;
    }

    /**
     * 把查询文本向量化。带缓存，失败返回空数组（调用方据此降级）。
     */
    public Mono<float[]> embedQuery(String text) {
        if (text == null || text.isBlank() || !available()) {
            return Mono.just(new float[0]);
        }

        String key = text.trim();
        float[] cached;
        synchronized (queryCache) {
            cached = queryCache.get(key);
        }
        if (cached != null) {
            return Mono.just(cached);
        }

        return embed(List.of(key))
                .map(vectors -> {
                    if (vectors.isEmpty()) {
                        return new float[0];
                    }
                    float[] v = vectors.get(0);
                    synchronized (queryCache) {
                        queryCache.put(key, v);
                    }
                    return v;
                })
                .onErrorResume(e -> {
                    // 熔断 60 秒：接口挂了就别每轮都去撞墙，让检索走关键词降级
                    circuitOpenUntil = System.currentTimeMillis() + 60_000L;
                    log.warn("embedding 调用失败，降级为关键词打分并熔断 60s: {}", e.toString());
                    return Mono.just(new float[0]);
                });
    }

    /**
     * 批量向量化。供回填脚本等场景复用，检索链路只用 {@link #embedQuery}。
     */
    public Mono<List<float[]>> embed(List<String> texts) {
        if (texts == null || texts.isEmpty() || !props.enabled()) {
            return Mono.just(List.of());
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.model());
        body.put("input", texts);
        // text-embedding-v2 支持指定维度，与库里的 dim 保持一致
        body.put("dimensions", props.dim());
        body.put("encoding_format", "float");

        return webClient.post()
                .uri("/embeddings")
                .header("Authorization", "Bearer " + props.apiKey())
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofMillis(props.timeoutMs() <= 0 ? 3000 : props.timeoutMs()))
                .map(this::parseVectors);
    }

    /** 解析 OpenAI 格式响应：{ data: [ { embedding: [...] }, ... ] } */
    private List<float[]> parseVectors(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode data = root.get("data");
            if (data == null || !data.isArray()) {
                return List.of();
            }
            List<float[]> out = new ArrayList<>(data.size());
            for (JsonNode item : data) {
                JsonNode emb = item.get("embedding");
                if (emb == null || !emb.isArray()) {
                    continue;
                }
                float[] v = new float[emb.size()];
                for (int i = 0; i < emb.size(); i++) {
                    v[i] = (float) emb.get(i).asDouble();
                }
                out.add(v);
            }
            return out;
        } catch (Exception e) {
            log.warn("embedding 响应解析失败: {}", e.toString());
            return List.of();
        }
    }

    /**
     * 余弦相似度，返回原始值（-1 ~ 1）。
     * <p>
     * 不做 (cos+1)/2 的映射。原因：中文商品文本的余弦分布很窄（实测 -0.09 ~ +0.32），
     * 线性映射到 0~1 后所有商品都挤在 0.45~0.66，区分度被抹平 ——
     * 结果是「保温杯」和「头戴降噪耳机」的语义分只差 0.03，
     * 加权后完全被评分销量盖过，排序错乱。
     * <p>
     * 归一化交给调用方在候选集内做 min-max，这样相对排序才是有效的。
     */
    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return -1;
        }
        float dot = 0f;
        float na = 0f;
        float nb = 0f;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0f || nb == 0f) {
            return -1;
        }
        return dot / (Math.sqrt((double) na) * Math.sqrt((double) nb));
    }

    /** 解析库里的 JSON 向量串。解析失败返回空数组，让调用方降级 */
    public static float[] parseStored(String json) {
        if (json == null || json.isBlank() || "null".equals(json)) {
            return new float[0];
        }
        String s = json.trim();
        if (!s.startsWith("[")) {
            return new float[0];
        }
        try {
            String body = s.substring(1, s.length() - 1).trim();
            if (body.isEmpty()) {
                return new float[0];
            }
            String[] parts = body.split(",");
            float[] v = new float[parts.length];
            for (int i = 0; i < parts.length; i++) {
                v[i] = Float.parseFloat(parts[i].trim());
            }
            return v;
        } catch (NumberFormatException e) {
            return new float[0];
        }
    }
}
