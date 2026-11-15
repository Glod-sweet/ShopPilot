package com.shoppilot.search;

import com.shoppilot.common.JsonUtils;
import com.shoppilot.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 混合检索服务 —— 本项目技术核心（handbook 4.3）。
 *
 * 执行顺序（与 handbook 的原始设计有意不同，原因见类注释末尾）：
 *   1. 场景识别：从用户输入命中 sp_scene.keywords → 得到场景ID
 *   2. 结构化过滤：上架 + 库存 + 价格 + 品类（场景内品类按权重）
 *   3. 语义相似度：query 向量化后与候选集的向量算余弦
 *   4. 重排序：场景契合度 0.5 + 语义相似 0.3 + 评分销量 0.2
 *   5. 返回 top N，附带推荐理由
 *
 * 与 handbook 的差异：原设计是「向量召回 top50 → 结构化过滤 → 重排序」。
 * 但 MySQL 社区版不支持向量索引（已实测），先向量召回等于全表扫描 + 内存排序，
 * 反而比「先过滤再算相似度」慢。当前数据量下先过滤是正确的，等切到 pgvector
 * 再改回先召回。
 *
 * 语义打分的两档：
 *   - 有向量（sp_product_embedding.dim > 0）→ 真余弦相似度
 *   - 无向量或向量接口不可用 → 降级为「关键词覆盖率 + 标题命中加权」
 * 降级不是摆设：本地没配 embedding key 时要能跑，接口抖动时也不能让检索挂掉。
 */
@Service
public class HybridSearchService {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchService.class);

    /**
     * 重排序权重。
     * <p>
     * 为什么语义权重最高（0.45）而不是场景分：
     * 场景分是「这个商品适合这个场景」的人工标注，表达的是**宽泛的适用性**；
     * 语义分是「这个商品符合用户这句话」的度量，表达的是**具体的匹配度**。
     * 用户说「通勤地铁太吵，想买个头戴的」时，「头戴」这个约束只有语义分能抓到 ——
     * 场景分只会说「保温杯也适合办公通勤」，于是保温杯排到了耳机前面。
     * <p>
     * 权重依据实测：场景分在 0.5~0.96 之间，语义归一化后 0~1 分布充分，
     * 把语义提到 0.45 后，明确的品类约束才能真正主导排序。
     * 场景分降到 0.30 但不清零，因为它能兜住「用户没明说但确实需要」的情况。
     */
    private static final double W_SEMANTIC = 0.45;
    private static final double W_SCENE = 0.30;
    private static final double W_QUALITY = 0.25;

    private final ProductRepository productRepo;
    private final EmbeddingService embeddingService;

    /** 场景关键词缓存，避免每次检索都查库（场景表很小且几乎不变） */
    private final AtomicReference<List<Map<String, Object>>> sceneCache = new AtomicReference<>();

    public HybridSearchService(ProductRepository productRepo, EmbeddingService embeddingService) {
        this.productRepo = productRepo;
        this.embeddingService = embeddingService;
    }

    /**
     * 主检索入口。
     *
     * @param query    用户原始输入，用于场景识别与语义相似度
     * @param category 品类ID（可为 null，走场景映射）
     * @param minPrice 价格下限
     * @param maxPrice 价格上限
     * @param limit    返回条数
     */
    public Mono<SearchResult> search(String query, Long category, BigDecimal minPrice,
                                     BigDecimal maxPrice, int limit) {
        return Mono.zip(loadScenes(), embeddingService.embedQuery(query))
                .flatMap(t -> {
                    List<Map<String, Object>> scenes = t.getT1();
                    float[] queryVector = t.getT2();

                    // 1) 场景识别
                    Optional<Map<String, Object>> matched = matchScene(query, scenes);
                    Long sceneId = matched.map(m -> ((Number) m.get("id")).longValue()).orElse(null);

                    boolean useVector = queryVector != null && queryVector.length > 0;
                    log.debug("检索 query={} 命中场景={} category={} price=[{},{}] 语义={}",
                            query, matched.map(m -> m.get("code")).orElse("none"),
                            category, minPrice, maxPrice, useVector ? "向量" : "关键词");

                    // 2) 结构化过滤拿候选（先过滤，理由见类注释）
                    return productRepo
                            .findCandidates(sceneId, category, minPrice, maxPrice, 200)
                            .collectList()
                            .map(candidates -> {
                                // 3) + 4) 语义相似 + 重排序
                                List<Scored> scored = rank(query, queryVector, candidates, sceneId != null);
                                int total = scored.size();

                                // 5) 取 top N
                                List<Map<String, Object>> top = scored.stream()
                                        .limit(Math.max(1, limit))
                                        .map(s -> toCard(s, query))
                                        .toList();

                                return new SearchResult(
                                        top,
                                        total,
                                        sceneId,
                                        matched.map(m -> (String) m.get("code")).orElse(null),
                                        scored.stream().map(s -> s.productId).toList(),
                                        useVector
                                );
                            });
                });
    }

    /** 场景识别：关键词命中即算命中，多个命中取排序靠前的（sp_scene.sort 越小越优先） */
    private Optional<Map<String, Object>> matchScene(String query, List<Map<String, Object>> scenes) {
        if (query == null || query.isBlank()) {
            return Optional.empty();
        }
        for (Map<String, Object> scene : scenes) {
            for (String k : parseJsonArray(scene.get("keywords"))) {
                if (!k.isBlank() && query.contains(k)) {
                    return Optional.of(scene);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * 解析 JSON 数组列。
     *
     * 必须自己解析而不能直接强转 List：MySQL 的 JSON 列经 R2DBC 驱动返回的是
     * String（如 ["生日","礼物"]），不是 java.util.List。
     * 之前直接 `instanceof List` 判断导致场景永远识别不出来，
     * 检索退化成全表按评分排序 —— 买礼物的人被推荐洗衣液。
     *
     * 这里用一个极简的 JSON 字符串数组解析，避免为一个小需求引入 Jackson 依赖链。
     */
    private List<String> parseJsonArray(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> list) {
            return list.stream().filter(java.util.Objects::nonNull)
                    .map(Object::toString).toList();
        }
        String s = raw.toString().trim();
        if (!s.startsWith("[") || !s.endsWith("]")) {
            // 不是数组就当成单值处理（例如 MySQL 返回的已是逗号分隔串）
            return s.isBlank() ? List.of() : List.of(s);
        }
        String body = s.substring(1, s.length() - 1).trim();
        if (body.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : body.split(",")) {
            String v = part.trim();
            if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
                v = v.substring(1, v.length() - 1);
            }
            // 处理 \" 这类转义
            v = v.replace("\\\"", "\"").replace("\\\\", "\\");
            if (!v.isBlank()) {
                out.add(v);
            }
        }
        return out;
    }

    private Mono<List<Map<String, Object>>> loadScenes() {
        List<Map<String, Object>> cached = sceneCache.get();
        if (cached != null) {
            return Mono.just(cached);
        }
        return productRepo.findAllScenes()
                .collectList()
                .doOnNext(list -> sceneCache.set(list));
    }

    /**
     * 打分与排序。
     *
     * 语义分两档：
     *   - queryVector 非空 → 与候选的存储向量算余弦（真语义）
     *   - queryVector 为空（没配 key / 接口挂了 / query 为空）→ 关键词近似降级
     * 每个候选单独判断：个别商品向量缺失时它自己走关键词，不影响其他商品用向量。
     * <p>
     * 关键一步是**候选集内 min-max 归一化**。中文商品文本的原始余弦分布很窄
     * （实测 -0.09 ~ +0.32），如果直接拿绝对值加权，所有商品的语义分都挤在一起，
     * 相对差异被场景分和评分销量淹没 —— 表现为「问头戴耳机，保温杯排第一」。
     * 归一化后 0 = 本批最不相关，1 = 本批最相关，相对排序才真正参与决策。
     */
    private List<Scored> rank(String query, float[] queryVector,
                              List<Map<String, Object>> candidates, boolean hasScene) {
        int n = candidates.size();
        if (n == 0) {
            return List.of();
        }
        boolean vectorUsable = queryVector != null && queryVector.length > 0;

        // 第一遍：算原始语义分
        double[] rawSemantic = new double[n];
        String[] semanticBy = new String[n];
        for (int i = 0; i < n; i++) {
            Map<String, Object> p = candidates.get(i);
            if (vectorUsable) {
                float[] stored = EmbeddingService.parseStored(str(p.get("embed_vector")));
                if (stored.length == queryVector.length) {
                    rawSemantic[i] = EmbeddingService.cosine(queryVector, stored);
                    semanticBy[i] = "vector";
                    continue;
                }
            }
            rawSemantic[i] = semanticScore(query, p);
            semanticBy[i] = "keyword";
        }

        // 第二遍：min-max 归一化。全批同分时给 0.5，避免除零且不偏袒任何一个
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (double v : rawSemantic) {
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        double span = max - min;

        // 第三遍：加权汇总
        List<Scored> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Map<String, Object> p = candidates.get(i);
            double semantic = span < 1e-6 ? 0.5 : (rawSemantic[i] - min) / span;

            double sceneScore = hasScene ? asDouble(p.get("scene_score"), 0.5) : 0.5;
            double rating = asDouble(p.get("rating"), 4.5);
            int sales = (int) asDouble(p.get("sales_count"), 0);
            // 评分归一到 0~1（5 分制），销量取对数避免头部商品一家独大
            double quality = (rating / 5.0) * 0.7 + Math.min(1.0, Math.log10(sales + 1) / 4.0) * 0.3;

            double total = W_SCENE * sceneScore + W_SEMANTIC * semantic + W_QUALITY * quality;
            list.add(new Scored(((Number) p.get("id")).longValue(), total, sceneScore,
                    semantic, semanticBy[i], rawSemantic[i], p));
        }
        list.sort(Comparator.comparingDouble((Scored s) -> s.total).reversed());
        return list;
    }

    /**
     * 轻量语义打分：query 的关键词在商品文本里的覆盖比例 + 标题命中加权。
     * 这是一个「够用就好」的近似，不是最终形态 —— 见方法内注释。
     */
    private double semanticScore(String query, Map<String, Object> p) {
        if (query == null || query.isBlank()) {
            return 0.5;
        }
        String title = str(p.get("title"));
        String content = str(p.get("embed_content"));
        String haystack = (title + " " + content + " " + str(p.get("category_name"))
                + " " + str(p.get("brand_name")) + " " + str(p.get("scene_reason"))).toLowerCase();

        // 从 query 里提取有意义的片段：中文按 2 字滑窗，英文/数字按词
        List<String> tokens = tokenize(query);
        if (tokens.isEmpty()) {
            return 0.5;
        }
        int hit = 0;
        double weightedHit = 0;
        for (String t : tokens) {
            if (haystack.contains(t)) {
                hit++;
                // 标题命中的权重是正文的 2 倍
                weightedHit += title.toLowerCase().contains(t) ? 2.0 : 1.0;
            }
        }
        double coverage = (double) hit / tokens.size();
        double titleBoost = Math.min(1.0, weightedHit / (tokens.size() * 2.0));
        return Math.min(1.0, coverage * 0.6 + titleBoost * 0.4);
    }

    /**
     * 中文没有空格，按 2 字滑窗切；同时剔除「想买」「以内」这类无信息量的停用词。
     * 这只是为了让冷启动可用，接 embedding 后此方法会被替换。
     */
    private List<String> tokenize(String query) {
        String cleaned = query.replaceAll("[\\p{Punct}\\s]+", " ")
                .replace("想买", " ").replace("买个", " ").replace("以内", " ")
                .replace("一下", " ").replace("帮我", " ").replace("推荐", " ")
                .replace("有没有", " ").replace("什么", " ");
        List<String> out = new ArrayList<>();
        for (String seg : cleaned.split("\\s+")) {
            if (seg.isBlank()) {
                continue;
            }
            if (seg.matches(".*[a-zA-Z0-9].*")) {
                out.add(seg.toLowerCase());
                continue;
            }
            if (seg.length() <= 2) {
                out.add(seg);
            } else {
                for (int i = 0; i + 2 <= seg.length(); i++) {
                    out.add(seg.substring(i, i + 2));
                }
            }
        }
        return out;
    }

    /** 转成 api-contract.md 4.2 定义的 product_card 结构 */
    private Map<String, Object> toCard(Scored s, String query) {
        Map<String, Object> p = s.product;
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("productId", s.productId);
        card.put("title", p.get("title"));
        card.put("subTitle", p.get("sub_title"));
        card.put("price", p.get("price"));
        card.put("originalPrice", p.get("original_price"));
        card.put("coverUrl", p.get("cover_url"));
        card.put("stock", p.get("stock"));
        card.put("salesCount", p.get("sales_count"));
        card.put("rating", p.get("rating"));
        // tags 是 JSON 列，R2DBC 读出来是字符串（如 ["礼物包装","顺丰包邮"]），
        // 必须转成真正的数组，否则前端会当成一段文本渲染
        card.put("tags", JsonUtils.toStringList(str(p.get("tags"))));
        card.put("categoryName", p.get("category_name"));
        card.put("brandName", p.get("brand_name"));
        // reason 是必填字段：优先用场景里人工标注的理由，其次用卖点摘要。
        // 注意这只是「兜底文案」——真正的场景化理由由 Agent 侧针对用户这句话重写，
        // 见 pilot-agent/app/reason.py。Java 拼的理由只保证字段不空。
        card.put("reason", buildReason(p, s));
        card.put("_score", Math.round(s.total * 1000) / 1000.0);
        // 打分明细：排查「为什么这件排第一」时不用再翻日志
        card.put("_sceneScore", Math.round(s.sceneScore * 1000) / 1000.0);
        card.put("_semanticScore", Math.round(s.semantic * 1000) / 1000.0);
        // 原始余弦（未归一化）。归一化后看不出「到底有多相关」，这个才有绝对值含义
        card.put("_rawSemantic", Math.round(s.rawSemantic * 10000) / 10000.0);
        card.put("_semanticBy", s.semanticBy);
        return card;
    }

    private String buildReason(Map<String, Object> p, Scored s) {
        String sceneReason = str(p.get("scene_reason"));
        if (!sceneReason.isBlank()) {
            return sceneReason;
        }
        String sub = str(p.get("sub_title"));
        if (!sub.isBlank()) {
            return sub;
        }
        String sp = str(p.get("selling_points"));
        if (sp.length() > 60) {
            return sp.substring(0, 60) + "…";
        }
        return sp.isBlank() ? "符合您的筛选条件" : sp;
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static double asDouble(Object o, double dft) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        if (o instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException ignored) {
                return dft;
            }
        }
        return dft;
    }

    private record Scored(long productId, double total, double sceneScore,
                          double semantic, String semanticBy, double rawSemantic,
                          Map<String, Object> product) {
    }

    /** 检索结果，带足够的过程信息便于排查与埋点 */
    public record SearchResult(
            List<Map<String, Object>> products,
            int candidateCount,
            Long sceneId,
            String sceneCode,
            List<Long> rankedIds,
            /** 本次语义打分用的是真向量还是关键词降级，排查排序异常时第一时间要看这个 */
            boolean vectorScored
    ) {
    }
}
