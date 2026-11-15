package com.shoppilot.repository;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 商品检索仓储。
 *
 * 这里实现 handbook 4.3 的「混合检索」，但按当前 MySQL 能力做了调整：
 *   1. 结构化过滤先做（上架/库存/价格/品类/场景）—— 把候选压到几百条
 *   2. 向量相似度在应用层算（MySQL 社区版无 DISTANCE()，已实测）
 *   3. 重排序综合 场景契合度 + 相似度 + 评分 + 销量
 *
 * 为什么先过滤再算相似度：如果先向量召回再过滤，价格/库存过滤后可能剩不下几条
 * （handbook 4.3 明确提到的问题）。反过来先过滤，候选集小且必然有效。
 */
@Repository
public class ProductRepository {

    private final DatabaseClient db;

    public ProductRepository(DatabaseClient db) {
        this.db = db;
    }

    /**
     * 结构化过滤：按场景/品类/价格/库存/上架筛出候选。
     * 注意 on_shelf=1 AND stock>0 是硬条件，种子数据里的「陷阱商品」靠这两条被拦掉。
     */
    public Flux<Map<String, Object>> findCandidates(
            Long sceneId,
            Long categoryId,
            BigDecimal minPrice,
            BigDecimal maxPrice,
            int limit
    ) {
        StringBuilder sql = new StringBuilder("""
                SELECT p.id, p.product_no, p.title, p.sub_title, p.category_id, p.brand_id,
                       p.price, p.original_price, p.cover_url, p.tags, p.selling_points,
                       p.attributes, p.stock, p.sales_count, p.rating, p.comment_count,
                       p.on_shelf, p.gift_wrappable,
                       c.name AS category_name,
                       b.name AS brand_name,
                       ps.score AS scene_score,
                       ps.reason AS scene_reason,
                       e.content AS embed_content,
                       e.embedding AS embed_vector
                FROM sp_product p
                JOIN sp_category c ON c.id = p.category_id
                LEFT JOIN sp_brand b ON b.id = p.brand_id
                LEFT JOIN sp_product_embedding e ON e.product_id = p.id
                """);

        // 场景处理：用 LEFT JOIN 而不是 JOIN。
        //
        // 为什么改成 LEFT JOIN：原来的 JOIN 会把候选集严格锁死在场景内，
        // 场景外但语义高度相关的商品被直接排除。实测踩到 ——
        // 用户问「通勤地铁太吵，想买个头戴的」，命中 office 场景后候选只剩 5 条，
        // 而语义余弦最高的「头戴降噪耳机」因为没被标注进 office 场景而完全消失。
        // 场景标注是人工维护的，必然有覆盖不全的情况，不该成为硬过滤条件。
        //
        // 现在改成：场景内的商品拿到 ps.score（0~1），场景外的拿 NULL。
        // 打分时 NULL 按场景分的中位数处理，语义分仍能把它顶上来。
        if (sceneId != null) {
            sql.append("LEFT JOIN sp_product_scene ps ON ps.product_id = p.id AND ps.scene_id = :sceneId ");
        } else {
            sql.append("LEFT JOIN sp_product_scene ps ON 1 = 0 ");
        }

        sql.append("WHERE p.on_shelf = 1 AND p.stock > 0 ");

        Map<String, Object> params = new LinkedHashMap<>();
        if (sceneId != null) {
            params.put("sceneId", sceneId);
        }
        if (categoryId != null) {
            sql.append("AND p.category_id = :categoryId ");
            params.put("categoryId", categoryId);
        }
        if (minPrice != null) {
            sql.append("AND p.price >= :minPrice ");
            params.put("minPrice", minPrice);
        }
        if (maxPrice != null) {
            sql.append("AND p.price <= :maxPrice ");
            params.put("maxPrice", maxPrice);
        }

        // 排序只是「取哪些进候选」，最终顺序由 HybridSearchService 重排决定。
        // 场景内优先，保证场景商品不会被 LIMIT 截掉；剩下的名额留给评分销量高的。
        if (sceneId != null) {
            sql.append("ORDER BY ps.score IS NULL, ps.score DESC, p.rating DESC, p.sales_count DESC ");
        } else {
            sql.append("ORDER BY p.rating DESC, p.sales_count DESC ");
        }
        sql.append("LIMIT :limit");
        params.put("limit", Math.min(Math.max(limit, 1), 200));

        var spec = db.sql(sql.toString());
        for (Map.Entry<String, Object> e : params.entrySet()) {
            spec = spec.bind(e.getKey(), e.getValue());
        }
        return spec.map(RowMappers::toMap).all();
    }

    /** 按关键词粗筛（无场景时的兜底），用于 title/selling_points 的 LIKE 匹配 */
    public Flux<Map<String, Object>> findByKeyword(String keyword, BigDecimal maxPrice, int limit) {
        String sql = """
                SELECT p.id, p.product_no, p.title, p.sub_title, p.category_id, p.brand_id,
                       p.price, p.original_price, p.cover_url, p.tags, p.selling_points,
                       p.attributes, p.stock, p.sales_count, p.rating, p.comment_count,
                       c.name AS category_name, b.name AS brand_name,
                       NULL AS scene_score, NULL AS scene_reason,
                       e.content AS embed_content,
                       e.embedding AS embed_vector
                FROM sp_product p
                JOIN sp_category c ON c.id = p.category_id
                LEFT JOIN sp_brand b ON b.id = p.brand_id
                LEFT JOIN sp_product_embedding e ON e.product_id = p.id
                WHERE p.on_shelf = 1 AND p.stock > 0
                  AND (p.title LIKE :kw OR p.selling_points LIKE :kw OR c.name LIKE :kw)
                """;
        String like = "%" + keyword + "%";
        if (maxPrice != null) {
            sql += " AND p.price <= :maxPrice";
        }
        sql += " ORDER BY p.rating DESC, p.sales_count DESC LIMIT :limit";

        var spec = db.sql(sql).bind("kw", like).bind("limit", Math.min(limit, 200));
        if (maxPrice != null) {
            spec = spec.bind("maxPrice", maxPrice);
        }
        return spec.map(RowMappers::toMap).all();
    }

    /** 单商品详情，供 get_product_detail 工具 */
    public Mono<Map<String, Object>> findById(long productId) {
        String sql = """
                SELECT p.*, c.name AS category_name, b.name AS brand_name
                FROM sp_product p
                JOIN sp_category c ON c.id = p.category_id
                LEFT JOIN sp_brand b ON b.id = p.brand_id
                WHERE p.id = :id
                """;
        return db.sql(sql).bind("id", productId)
                .map(RowMappers::toMap).one();
    }

    /** 按场景码查场景ID与预算提示，用于槽位追问时给默认选项 */
    public Mono<Map<String, Object>> findSceneByCode(String code) {
        return db.sql("""
                SELECT id, code, name, budget_hint_min, budget_hint_max, keywords
                FROM sp_scene WHERE code = :code
                """).bind("code", code)
                .map(RowMappers::toMap).one();
    }

    /** 全部场景，供意图识别做关键词匹配 */
    public Flux<Map<String, Object>> findAllScenes() {
        return db.sql("""
                SELECT id, code, name, keywords, budget_hint_min, budget_hint_max
                FROM sp_scene ORDER BY sort
                """).map(RowMappers::toMap).all();
    }

    /** 某场景下的品类权重，用于把「生日礼物」映射到「香薰/口红/项链」 */
    public Flux<Map<String, Object>> findCategoriesByScene(long sceneId) {
        return db.sql("""
                SELECT sc.category_id, c.name, sc.weight
                FROM sp_scene_category sc
                JOIN sp_category c ON c.id = sc.category_id
                WHERE sc.scene_id = :sceneId
                ORDER BY sc.weight DESC
                """).bind("sceneId", sceneId)
                .map(RowMappers::toMap).all();
    }

    /** 健康检查用：确认数据库真的可查 */
    public Mono<Long> countProducts() {
        return db.sql("SELECT COUNT(*) AS c FROM sp_product")
                .map((row, meta) -> {
                    Long c = row.get("c", Long.class);
                    return c == null ? 0L : c;
                }).one();
    }

    // =========================================================
    // 以下为商城前台（非 Agent 链路）所需的查询
    // =========================================================

    /**
     * 商品分页列表。
     * <p>
     * 为什么要 includeChildren：用户点「数码配件」时想看全部二级品类下的商品，
     * 但库里商品只挂二级品类，直接按一级 ID 查会是空列表。
     * 这里用子查询展开，避免前端多打一次请求。
     */
    public Flux<Map<String, Object>> pageProducts(
            Long categoryId, Long brandId, BigDecimal minPrice, BigDecimal maxPrice,
            String keyword, String orderBy, int offset, int size, boolean includeChildren) {

        StringBuilder sql = new StringBuilder("""
                SELECT p.id, p.product_no, p.title, p.sub_title, p.category_id, p.brand_id,
                       p.price, p.original_price, p.cover_url, p.tags, p.stock,
                       p.sales_count, p.rating, p.comment_count, p.gift_wrappable,
                       c.name AS category_name, b.name AS brand_name
                FROM sp_product p
                JOIN sp_category c ON c.id = p.category_id
                LEFT JOIN sp_brand b ON b.id = p.brand_id
                WHERE p.on_shelf = 1
                """);

        Map<String, Object> params = new LinkedHashMap<>();
        appendProductFilters(sql, params, categoryId, brandId, minPrice, maxPrice, keyword, includeChildren);

        sql.append(" ORDER BY ").append(orderBy).append(" LIMIT :size OFFSET :offset");
        params.put("size", size);
        params.put("offset", offset);

        return bind(db.sql(sql.toString()), params).map(RowMappers::toMap).all();
    }

    /** 与列表同条件的总数，供分页器使用 */
    public Mono<Long> countProducts(Long categoryId, Long brandId, BigDecimal minPrice,
                                    BigDecimal maxPrice, String keyword, boolean includeChildren) {
        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(*) AS c
                FROM sp_product p
                JOIN sp_category c ON c.id = p.category_id
                WHERE p.on_shelf = 1
                """);
        Map<String, Object> params = new LinkedHashMap<>();
        appendProductFilters(sql, params, categoryId, brandId, minPrice, maxPrice, keyword, includeChildren);

        return bind(db.sql(sql.toString()), params)
                .map((row, meta) -> {
                    Long c = row.get("c", Long.class);
                    return c == null ? 0L : c;
                }).one();
    }

    /** 列表与计数共用的 WHERE 片段，保证两者条件绝对一致，否则分页会出现空页 */
    private void appendProductFilters(StringBuilder sql, Map<String, Object> params,
                                      Long categoryId, Long brandId,
                                      BigDecimal minPrice, BigDecimal maxPrice,
                                      String keyword, boolean includeChildren) {
        if (categoryId != null) {
            if (includeChildren) {
                sql.append(" AND (p.category_id = :categoryId OR c.parent_id = :categoryId) ");
            } else {
                sql.append(" AND p.category_id = :categoryId ");
            }
            params.put("categoryId", categoryId);
        }
        if (brandId != null) {
            sql.append(" AND p.brand_id = :brandId ");
            params.put("brandId", brandId);
        }
        if (minPrice != null) {
            sql.append(" AND p.price >= :minPrice ");
            params.put("minPrice", minPrice);
        }
        if (maxPrice != null) {
            sql.append(" AND p.price <= :maxPrice ");
            params.put("maxPrice", maxPrice);
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (p.title LIKE :kw OR p.sub_title LIKE :kw OR p.selling_points LIKE :kw) ");
            params.put("kw", "%" + keyword.trim() + "%");
        }
    }

    /** 参数绑定收敛到一处，避免每个方法重复写 for 循环 */
    private org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec bind(
            org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec spec,
            Map<String, Object> params) {
        var result = spec;
        for (Map.Entry<String, Object> e : params.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            result = result.bind(e.getKey(), e.getValue());
        }
        return result;
    }

    /** 商品的 SKU 列表，详情页与「确认规格」环节使用 */
    public Flux<Map<String, Object>> findSkusByProductId(long productId) {
        return db.sql("""
                        SELECT id, sku_code, spec, price, stock, image_url, status
                        FROM sp_sku
                        WHERE product_id = :productId
                        ORDER BY id
                        """)
                .bind("productId", productId)
                .map(RowMappers::toMap).all();
    }

    /** 商品适用的场景 + 该场景下的推荐理由 */
    public Flux<Map<String, Object>> findScenesByProductId(long productId) {
        return db.sql("""
                        SELECT s.id AS scene_id, s.code AS scene_code, s.name AS scene_name,
                               ps.score, ps.reason
                        FROM sp_product_scene ps
                        JOIN sp_scene s ON s.id = ps.scene_id
                        WHERE ps.product_id = :productId
                        ORDER BY ps.score DESC
                        """)
                .bind("productId", productId)
                .map(RowMappers::toMap).all();
    }

    /** 全部品类，供构建品类树 */
    public Flux<Map<String, Object>> findAllCategories() {
        return db.sql("""
                        SELECT id, parent_id, name, level, sort
                        FROM sp_category
                        ORDER BY level, sort, id
                        """)
                .map(RowMappers::toMap).all();
    }

    /**
     * 按品类名模糊查 ID，供 Agent 传中文品类（「耳机」「香薰」）时解析。
     * <p>
     * 为什么需要它：Agent 的工具参数是自然语言里提炼的品类词，
     * 而检索需要的是 category_id。没有这一步，用户说「想买耳机」时
     * 品类条件会被静默丢弃，退化成全品类按评分排序。
     * <p>
     * 优先精确匹配，其次包含匹配，最后取排序靠前的二级品类。
     */
    public Mono<Long> findCategoryIdByName(String name) {
        if (name == null || name.isBlank()) {
            return Mono.empty();
        }
        return db.sql("""
                        SELECT id FROM sp_category
                        WHERE name = :name
                        ORDER BY level DESC, sort, id
                        LIMIT 1
                        """)
                .bind("name", name.trim())
                .map((row, meta) -> row.get("id", Long.class))
                .one()
                .switchIfEmpty(Mono.defer(() -> db.sql("""
                                SELECT id FROM sp_category
                                WHERE name LIKE :kw
                                ORDER BY level DESC, sort, id
                                LIMIT 1
                                """)
                        .bind("kw", "%" + name.trim() + "%")
                        .map((row, meta) -> row.get("id", Long.class))
                        .one()));
    }

    /** 全部品牌，前端筛选栏用 */
    public Flux<Map<String, Object>> findAllBrands() {
        return db.sql("""
                        SELECT b.id, b.name, b.logo_url, b.description,
                               (SELECT COUNT(*) FROM sp_product p
                                WHERE p.brand_id = b.id AND p.on_shelf = 1) AS product_count
                        FROM sp_brand b
                        ORDER BY b.sort, b.id
                        """)
                .map(RowMappers::toMap).all();
    }

    /** 单商品库存（含 SKU 级），供 check_stock 工具 */
    public Mono<Map<String, Object>> findStock(long productId, Long skuId) {
        if (skuId == null) {
            return db.sql("""
                            SELECT p.id AS product_id, p.stock, p.on_shelf
                            FROM sp_product p WHERE p.id = :productId
                            """)
                    .bind("productId", productId)
                    .map(RowMappers::toMap).one();
        }
        return db.sql("""
                        SELECT s.id AS sku_id, s.product_id, s.stock, s.status,
                               p.on_shelf, p.title
                        FROM sp_sku s
                        JOIN sp_product p ON p.id = s.product_id
                        WHERE s.id = :skuId AND s.product_id = :productId
                        """)
                .bind("skuId", skuId)
                .bind("productId", productId)
                .map(RowMappers::toMap).one();
    }
}
