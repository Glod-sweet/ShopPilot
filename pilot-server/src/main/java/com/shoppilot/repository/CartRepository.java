package com.shoppilot.repository;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 购物车仓储。所有写操作只在这里发生。
 * <p>
 * 关键约束：加购/改数量都不校验库存是否足够——库存校验留到下单那一刻，
 * 用「带条件的 UPDATE」做原子扣减。理由是购物车是意向而非承诺，
 * 提前锁库存会导致商品被长期占用。
 */
@Repository
public class CartRepository {

    private final DatabaseClient db;

    public CartRepository(DatabaseClient db) {
        this.db = db;
    }

    /**
     * 购物车列表。JOIN 商品与 SKU 带出实时价格、库存、上下架状态，
     * 而不是依赖加购时的快照——用户看到的必须是当前可成交的信息。
     */
    public Flux<Map<String, Object>> findItems(long userId) {
        return db.sql("""
                        SELECT ci.id AS cart_item_id, ci.product_id, ci.sku_id, ci.quantity,
                               ci.selected, ci.source,
                               p.title, p.sub_title, p.cover_url, p.stock, p.on_shelf,
                               p.price AS product_price,
                               s.spec, s.price AS sku_price, s.stock AS sku_stock, s.status AS sku_status
                        FROM sp_cart_item ci
                        JOIN sp_product p ON p.id = ci.product_id
                        LEFT JOIN sp_sku s ON s.id = ci.sku_id
                        WHERE ci.user_id = :userId
                        ORDER BY ci.created_at DESC, ci.id DESC
                        """)
                .bind("userId", userId)
                .map(RowMappers::toMap).all();
    }

    /** 加购：同商品同规格已存在则累加数量，避免购物车里出现重复行 */
    public Mono<Long> upsert(long userId, long productId, Long skuId, int quantity,
                             String source, Long sessionId) {
        return findExistingItem(userId, productId, skuId)
                .flatMap(existing -> {
                    Long id = (Long) existing.get("id");
                    int oldQty = existing.get("quantity") instanceof Number n ? n.intValue() : 0;
                    return db.sql("""
                                    UPDATE sp_cart_item
                                    SET quantity = :qty, selected = 1, updated_at = NOW()
                                    WHERE id = :id
                                    """)
                            .bind("qty", Math.min(oldQty + quantity, 99))
                            .bind("id", id)
                            .fetch().rowsUpdated()
                            .thenReturn(id);
                })
                .switchIfEmpty(Mono.defer(() -> insertItem(userId, productId, skuId, quantity, source, sessionId)));
    }

    private Mono<Map<String, Object>> findExistingItem(long userId, long productId, Long skuId) {
        // SKU 为 null 时用 IS NULL 比较，不能写 sku_id = NULL（永远不成立）
        String sql = skuId == null
                ? """
                  SELECT id, quantity FROM sp_cart_item
                  WHERE user_id = :userId AND product_id = :productId AND sku_id IS NULL
                  LIMIT 1
                  """
                : """
                  SELECT id, quantity FROM sp_cart_item
                  WHERE user_id = :userId AND product_id = :productId AND sku_id = :skuId
                  LIMIT 1
                  """;
        var spec = db.sql(sql).bind("userId", userId).bind("productId", productId);
        if (skuId != null) {
            spec = spec.bind("skuId", skuId);
        }
        return spec.map(RowMappers::toMap).one();
    }

    private Mono<Long> insertItem(long userId, long productId, Long skuId, int quantity,
                                  String source, Long sessionId) {
        var spec = db.sql("""
                        INSERT INTO sp_cart_item
                            (user_id, product_id, sku_id, quantity, selected, source, session_id)
                        VALUES (:userId, :productId, :skuId, :quantity, 1, :source, :sessionId)
                        """)
                .bind("userId", userId)
                .bind("productId", productId)
                .bind("quantity", quantity)
                .bind("source", source == null ? "user" : source);

        // R2DBC 的 bindNull 必须显式给类型，否则驱动无法推断
        spec = skuId == null ? spec.bindNull("skuId", Long.class) : spec.bind("skuId", skuId);
        spec = sessionId == null ? spec.bindNull("sessionId", Long.class) : spec.bind("sessionId", sessionId);

        // 不用 SELECT LAST_INSERT_ID()：R2DBC 下两条语句可能落在不同连接上，
        // 拿到的会是 0 或别的会话的值。插入后按唯一业务键回查最可靠。
        return spec.fetch().rowsUpdated()
                .then(findExistingItem(userId, productId, skuId))
                .map(row -> (Long) row.get("id"))
                .switchIfEmpty(Mono.error(new IllegalStateException("加购后回查失败")));
    }

    /** 改数量。quantity <= 0 等价于删除，由 Service 层决定语义 */
    public Mono<Long> updateQuantity(long userId, long cartItemId, int quantity) {
        return db.sql("""
                        UPDATE sp_cart_item SET quantity = :qty, updated_at = NOW()
                        WHERE id = :id AND user_id = :userId
                        """)
                .bind("qty", quantity)
                .bind("id", cartItemId)
                .bind("userId", userId)
                .fetch().rowsUpdated();
    }

    public Mono<Long> updateSelected(long userId, long cartItemId, boolean selected) {
        return db.sql("""
                        UPDATE sp_cart_item SET selected = :selected, updated_at = NOW()
                        WHERE id = :id AND user_id = :userId
                        """)
                .bind("selected", selected ? 1 : 0)
                .bind("id", cartItemId)
                .bind("userId", userId)
                .fetch().rowsUpdated();
    }

    /** 全选/全不选 */
    public Mono<Long> updateAllSelected(long userId, boolean selected) {
        return db.sql("""
                        UPDATE sp_cart_item SET selected = :selected, updated_at = NOW()
                        WHERE user_id = :userId
                        """)
                .bind("selected", selected ? 1 : 0)
                .bind("userId", userId)
                .fetch().rowsUpdated();
    }

    /** 删除单条。带 user_id 条件，防止越权删别人的购物车 */
    public Mono<Long> deleteItem(long userId, long cartItemId) {
        return db.sql("DELETE FROM sp_cart_item WHERE id = :id AND user_id = :userId")
                .bind("id", cartItemId)
                .bind("userId", userId)
                .fetch().rowsUpdated();
    }

    public Mono<Long> deleteItems(long userId, Iterable<Long> cartItemIds) {
        var ids = new java.util.ArrayList<Long>();
        cartItemIds.forEach(ids::add);
        if (ids.isEmpty()) {
            return Mono.just(0L);
        }
        return db.sql("DELETE FROM sp_cart_item WHERE user_id = :userId AND id IN (:ids)")
                .bind("userId", userId)
                .bind("ids", ids)
                .fetch().rowsUpdated();
    }

    public Mono<Long> countItems(long userId) {
        return db.sql("SELECT COUNT(*) AS c FROM sp_cart_item WHERE user_id = :userId")
                .bind("userId", userId)
                .map((row, meta) -> {
                    Long c = row.get("c", Long.class);
                    return c == null ? 0L : c;
                }).one();
    }

    /**
     * 结算用：按 ID 集合取出待结算条目。
     * 只取 selected=1 且商品在架的行——下单前的最后一道过滤。
     */
    public Flux<Map<String, Object>> findCheckoutItems(long userId, Iterable<Long> cartItemIds) {
        var ids = new java.util.ArrayList<Long>();
        cartItemIds.forEach(ids::add);

        String sql = ids.isEmpty()
                ? """
                  SELECT ci.id AS cart_item_id, ci.product_id, ci.sku_id, ci.quantity,
                         p.title, p.price AS product_price, p.stock, p.on_shelf,
                         s.spec, s.price AS sku_price, s.stock AS sku_stock, s.status AS sku_status
                  FROM sp_cart_item ci
                  JOIN sp_product p ON p.id = ci.product_id
                  LEFT JOIN sp_sku s ON s.id = ci.sku_id
                  WHERE ci.user_id = :userId AND ci.selected = 1
                  ORDER BY ci.id
                  """
                : """
                  SELECT ci.id AS cart_item_id, ci.product_id, ci.sku_id, ci.quantity,
                         p.title, p.price AS product_price, p.stock, p.on_shelf,
                         s.spec, s.price AS sku_price, s.stock AS sku_stock, s.status AS sku_status
                  FROM sp_cart_item ci
                  JOIN sp_product p ON p.id = ci.product_id
                  LEFT JOIN sp_sku s ON s.id = ci.sku_id
                  WHERE ci.user_id = :userId AND ci.id IN (:ids)
                  ORDER BY ci.id
                  """;

        var spec = db.sql(sql).bind("userId", userId);
        if (!ids.isEmpty()) {
            spec = spec.bind("ids", ids);
        }
        return spec.map(RowMappers::toMap).all();
    }

    /** 清空已结算条目 */
    public Mono<Long> clearCheckedOut(long userId, Iterable<Long> cartItemIds) {
        return deleteItems(userId, cartItemIds);
    }

    /** 便于调试：查某用户购物车的原始行 */
    public Mono<Map<String, Object>> debugOne(long cartItemId) {
        return db.sql("SELECT * FROM sp_cart_item WHERE id = :id")
                .bind("id", cartItemId)
                .map(RowMappers::toMap).one()
                .defaultIfEmpty(new LinkedHashMap<>());
    }
}
