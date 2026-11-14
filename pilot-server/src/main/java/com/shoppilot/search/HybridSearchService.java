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
 *   3. 向量相似度：在候选集内算余弦（MySQL 社区版无 DISTANCE()）
 *   4. 重排序：场景契合度 0.5 + 语义相似 0.3 + 评分销量 0.2
 *   5. 返回 top N，附带推荐理由
 *
 * 与 handbook 的差异：原设计是「向量召回 top50 → 结构化过滤 → 重排序」。
 * 但 MySQL 社区版不支持向量索引（已实测），先向量召回等于全表扫描 + 内存排序，
 * 反而比「先过滤再算相似度」慢。当前数据量下先过滤是正确的，等切到 pgvector
 * 再改回先召回。
 */
@Service
public class HybridSearchService {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchService.class);

    /** 重排序权重：场景契合度最重要，因为它是人工标注的强信号 */
    private static final double W_SCENE = 0.50;
    private static final double W_SEMANTIC = 0.30;
    private static final double W_QUALITY = 0.20;

    private final ProductRepository productRepo;

    /** 场景关键词缓存，避免每次检索都查库（场景表很小且几乎不变） */
    private final AtomicReference<List<Map<String, Object>>> sceneCache = new AtomicReference<>();

    public HybridSearchService(ProductRepository productRepo) {
        this.productRepo = productRepo;
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
        return loadScenes()
                .flatMap(scenes -> {
                    // 1) 场景识别
                    Optional<Map<String, Object>> matched = matchScene(query, scenes);
                    Long sceneId = matched.map(m -> ((Number) m.get("id")).longValue()).orElse(null);

                    log.debug("检索 query={} 命中场景={} category={} price=[{},{}]",
                            query, matched.map(m -> m.get("code")).orElse("none"),
                            category, minPrice, maxPrice);

                    // 2) 结构化过滤拿候选（先过滤，理由见类注释）
                    return productRepo
                            .findCandidates(sceneId, category, minPrice, maxPrice, 200)
                            .collectList()
                            .map(candidates -> {
                                // 3) + 4) 语义相似 + 重排序
                                List<Scored> scored = rank(query, candidates, sceneId != null);
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
                                        scored.stream().map(s -> s.productId).toList()
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
     * 语义相似度用「字符二元组 + 关键词覆盖率」的轻量近似，不依赖 embedding：
     * 当前 embedding 尚未回填（dim=0），用真向量反而全是 0 分。
     * 等 embedding 回填后，把 semanticScore 换成余弦即可，其余逻辑不动。
     */
    private List<Scored> rank(String query, List<Map<String, Object>> candidates, boolean hasScene) {
        List<Scored> list = new ArrayList<>(candidates.size());
        for (Map<String, Object> p : candidates) {
            double sceneScore = hasScene ? asDouble(p.get("scene_score"), 0.5) : 0.5;
            double semantic = semanticScore(query, p);
            double rating = asDouble(p.get("rating"), 4.5);
            int sales = (int) asDouble(p.get("sales_count"), 0);
            // 评分归一到 0~1（5 分制），销量取对数避免头部商品一家独大
            double quality = (rating / 5.0) * 0.7 + Math.min(1.0, Math.log10(sales + 1) / 4.0) * 0.3;

            double total = W_SCENE * sceneScore + W_SEMANTIC * semantic + W_QUALITY * quality;
            list.add(new Scored(((Number) p.get("id")).longValue(), total, sceneScore, semantic, p));
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
        // reason 是必填字段：优先用场景里人工标注的理由，其次用卖点摘要
        card.put("reason", buildReason(p, s));
        card.put("_score", Math.round(s.total * 1000) / 1000.0);
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
                          double semantic, Map<String, Object> product) {
    }

    /** 检索结果，带足够的过程信息便于排查与埋点 */
    public record SearchResult(
            List<Map<String, Object>> products,
            int candidateCount,
            Long sceneId,
            String sceneCode,
            List<Long> rankedIds
    ) {
    }
}
