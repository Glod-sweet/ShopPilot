package com.shoppilot.repository;

import com.shoppilot.common.RowValues;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 订单仓储。写操作只在这里发生，Python 无法直接触达。
 * <p>
 * 关于「再来一单」：{@link #findRecentOrders} 会带出商品当前库存/价格/上下架，
 * 并标注 anomaly。这样 Agent 能据实说明「已下架」「涨价了」，
 * 而不是编造一个不存在的商品——这是 handbook 4.2 明确要求的异常处理。
 * <p>
 * 购物车相关读写已拆到 {@link CartRepository}，本类只负责订单。
 */
@Repository
public class OrderRepository {

    private final DatabaseClient db;

    public OrderRepository(DatabaseClient db) {
        this.db = db;
    }

    /**
     * 最近订单，支撑「再来一单」。
     * 只取已付款及之后的订单（status 1/2/3）：待付款和已取消的订单
     * 不代表用户真实购买行为，拿它推荐会推荐错东西。
     */
    public Flux<Map<String, Object>> findRecentOrders(long userId, int limit) {
        return db.sql("""
                        SELECT o.id AS order_id, o.order_no, o.created_at AS order_time,
                               i.product_id, i.product_title, i.sku_spec, i.price AS order_price,
                               i.quantity,
                               p.price AS current_price, p.stock AS current_stock,
                               p.on_shelf AS current_on_shelf, p.cover_url AS cover_url
                        FROM sp_order o
                        JOIN sp_order_item i ON i.order_id = o.id
                        LEFT JOIN sp_product p ON p.id = i.product_id
                        WHERE o.user_id = :userId AND o.status IN (1, 2, 3)
                        ORDER BY o.created_at DESC
                        LIMIT :limit
                        """).bind("userId", userId)
                .bind("limit", Math.min(Math.max(limit, 1), 50))
                .map((row, meta) -> {
                    Map<String, Object> m = RowMappers.toMap(row, meta);
                    m.put("anomaly", detectAnomaly(m));
                    return m;
                }).all();
    }

    /** 判断「再来一单」时的异常，让 Agent 有话可说而不是硬推 */
    private String detectAnomaly(Map<String, Object> m) {
        Object onShelf = m.get("current_on_shelf");
        Object stock = m.get("current_stock");

        // 用 RowValues 而非 instanceof Number：TINYINT(1) 可能被驱动读成 Boolean
        if (onShelf != null && RowValues.asInt(onShelf, 1) == 0) {
            return "OFF_SHELF";
        }
        if (stock != null && RowValues.asInt(stock, 0) <= 0) {
            return "OUT_OF_STOCK";
        }
        BigDecimal op = RowValues.asDecimal(m.get("order_price"));
        BigDecimal np = RowValues.asDecimal(m.get("current_price"));
        if (op != null && np != null && np.compareTo(op) != 0) {
            return "PRICE_CHANGED";
        }
        return null;
    }

    /** 用户画像，供 get_user_profile 工具 */
    public Mono<Map<String, Object>> findUserProfile(long userId) {
        return db.sql("""
                        SELECT u.id, u.nickname, up.budget_min, up.budget_max,
                               up.preferred_categories, up.preferred_brands,
                               up.avg_order_amount, up.order_count
                        FROM sp_user u
                        LEFT JOIN sp_user_profile up ON up.user_id = u.id
                        WHERE u.id = :userId
                        """).bind("userId", userId)
                .map(RowMappers::toMap).one();
    }

    // =========================================================
    // 下单链路
    // =========================================================

    /**
     * 原子扣库存。
     * <p>
     * 用「带条件的 UPDATE」而不是「先查再改」：
     *   UPDATE ... SET stock = stock - n WHERE id = ? AND stock >= n
     * rowsUpdated == 0 就说明被别人抢光了，直接失败。
     * 这是单机并发下最可靠的扣减方式，不需要分布式锁。
     */
    public Mono<Boolean> deductStock(long productId, int quantity) {
        return db.sql("""
                        UPDATE sp_product SET stock = stock - :qty
                        WHERE id = :id AND stock >= :qty AND on_shelf = 1
                        """)
                .bind("qty", quantity)
                .bind("id", productId)
                .fetch().rowsUpdated()
                .map(rows -> rows != null && rows > 0);
    }

    /** SKU 级扣减，供多规格商品使用 */
    public Mono<Boolean> deductSkuStock(long skuId, int quantity) {
        return db.sql("""
                        UPDATE sp_sku SET stock = stock - :qty
                        WHERE id = :id AND stock >= :qty AND status = 1
                        """)
                .bind("qty", quantity)
                .bind("id", skuId)
                .fetch().rowsUpdated()
                .map(rows -> rows != null && rows > 0);
    }

    /** 取消订单时回补库存 */
    public Mono<Long> restoreStock(long productId, int quantity) {
        return db.sql("UPDATE sp_product SET stock = stock + :qty WHERE id = :id")
                .bind("qty", quantity)
                .bind("id", productId)
                .fetch().rowsUpdated();
    }

    public Mono<Long> restoreSkuStock(long skuId, int quantity) {
        return db.sql("UPDATE sp_sku SET stock = stock + :qty WHERE id = :id")
                .bind("qty", quantity)
                .bind("id", skuId)
                .fetch().rowsUpdated();
    }

    /** 写订单主表，返回自增 ID */
    public Mono<Long> insertOrder(String orderNo, long userId, BigDecimal totalAmount,
                                  BigDecimal payAmount, String source, Long sessionId) {
        var spec = db.sql("""
                        INSERT INTO sp_order
                            (order_no, user_id, total_amount, pay_amount, status, source, session_id)
                        VALUES (:orderNo, :userId, :totalAmount, :payAmount, 0, :source, :sessionId)
                        """)
                .bind("orderNo", orderNo)
                .bind("userId", userId)
                .bind("totalAmount", totalAmount)
                .bind("payAmount", payAmount)
                .bind("source", source == null ? "web" : source);

        spec = sessionId == null ? spec.bindNull("sessionId", Long.class) : spec.bind("sessionId", sessionId);

        return spec.fetch().rowsUpdated()
                // 订单号有唯一索引，回查比 LAST_INSERT_ID() 可靠（R2DBC 不保证同连接）
                .then(db.sql("SELECT id FROM sp_order WHERE order_no = :orderNo")
                        .bind("orderNo", orderNo)
                        .map((row, meta) -> row.get("id", Long.class))
                        .one());
    }

    /** 写订单明细。商品标题与规格做快照，商品改名或下架不影响历史订单 */
    public Mono<Long> insertOrderItem(long orderId, long productId, Long skuId,
                                      String productTitle, String skuSpec,
                                      BigDecimal price, int quantity, BigDecimal amount) {
        var spec = db.sql("""
                        INSERT INTO sp_order_item
                            (order_id, product_id, sku_id, product_title, sku_spec, price, quantity, amount)
                        VALUES (:orderId, :productId, :skuId, :title, :spec, :price, :quantity, :amount)
                        """)
                .bind("orderId", orderId)
                .bind("productId", productId)
                .bind("title", productTitle)
                .bind("price", price)
                .bind("quantity", quantity)
                .bind("amount", amount);

        // 单规格商品没有 spec，必须 bindNull；直接 bind("spec", null) 会被驱动拒绝
        spec = skuSpec == null ? spec.bindNull("spec", String.class) : spec.bind("spec", skuSpec);
        spec = skuId == null ? spec.bindNull("skuId", Long.class) : spec.bind("skuId", skuId);

        return spec.fetch().rowsUpdated();
    }

    /** 订单主表信息（含用户归属，用于越权校验） */
    public Mono<Map<String, Object>> findOrder(long orderId, long userId) {
        return db.sql("""
                        SELECT id, order_no, user_id, total_amount, pay_amount, status,
                               source, created_at, paid_at
                        FROM sp_order
                        WHERE id = :id AND user_id = :userId
                        """)
                .bind("id", orderId)
                .bind("userId", userId)
                .map(RowMappers::toMap).one();
    }

    public Flux<Map<String, Object>> findOrderItems(long orderId) {
        return db.sql("""
                        SELECT product_id, sku_id, product_title, sku_spec, price, quantity, amount
                        FROM sp_order_item
                        WHERE order_id = :orderId
                        ORDER BY id
                        """)
                .bind("orderId", orderId)
                .map(RowMappers::toMap).all();
    }

    /** 订单列表（我的订单页） */
    public Flux<Map<String, Object>> pageOrders(long userId, Integer status, int offset, int size) {
        String sql = status == null
                ? """
                  SELECT id, order_no, total_amount, pay_amount, status, source, created_at, paid_at
                  FROM sp_order WHERE user_id = :userId
                  ORDER BY created_at DESC, id DESC
                  LIMIT :size OFFSET :offset
                  """
                : """
                  SELECT id, order_no, total_amount, pay_amount, status, source, created_at, paid_at
                  FROM sp_order WHERE user_id = :userId AND status = :status
                  ORDER BY created_at DESC, id DESC
                  LIMIT :size OFFSET :offset
                  """;
        var spec = db.sql(sql).bind("userId", userId).bind("size", size).bind("offset", offset);
        if (status != null) {
            spec = spec.bind("status", status);
        }
        return spec.map(RowMappers::toMap).all();
    }

    public Mono<Long> countOrders(long userId, Integer status) {
        String sql = status == null
                ? "SELECT COUNT(*) AS c FROM sp_order WHERE user_id = :userId"
                : "SELECT COUNT(*) AS c FROM sp_order WHERE user_id = :userId AND status = :status";
        var spec = db.sql(sql).bind("userId", userId);
        if (status != null) {
            spec = spec.bind("status", status);
        }
        return spec.map((row, meta) -> {
            Long c = row.get("c", Long.class);
            return c == null ? 0L : c;
        }).one();
    }

    /**
     * 状态流转。带 fromStatus 条件保证幂等：
     * 重复点击「取消」只有第一次会成功，第二次 rowsUpdated=0。
     */
    public Mono<Boolean> updateStatus(long orderId, long userId, int fromStatus, int toStatus) {
        return db.sql("""
                        UPDATE sp_order SET status = :to
                        WHERE id = :id AND user_id = :userId AND status = :from
                        """)
                .bind("to", toStatus)
                .bind("id", orderId)
                .bind("userId", userId)
                .bind("from", fromStatus)
                .fetch().rowsUpdated()
                .map(rows -> rows != null && rows > 0);
    }

    /** 标记支付时间（本项目不对接支付渠道，仅提供接口占位） */
    public Mono<Boolean> markPaid(long orderId, long userId) {
        return db.sql("""
                        UPDATE sp_order SET status = 1, paid_at = :now
                        WHERE id = :id AND user_id = :userId AND status = 0
                        """)
                .bind("now", LocalDateTime.now())
                .bind("id", orderId)
                .bind("userId", userId)
                .fetch().rowsUpdated()
                .map(rows -> rows != null && rows > 0);
    }

    /** 统计用户历史订单数，下单后更新画像用 */
    public Mono<Long> countPaidOrders(long userId) {
        return db.sql("""
                        SELECT COUNT(*) AS c FROM sp_order
                        WHERE user_id = :userId AND status IN (1,2,3)
                        """)
                .bind("userId", userId)
                .map((row, meta) -> {
                    Long c = row.get("c", Long.class);
                    return c == null ? 0L : c;
                }).one();
    }

    /** 更新用户画像的客单价与订单数，供下次个性化推荐 */
    public Mono<Long> refreshUserProfile(long userId, BigDecimal avgAmount, int orderCount) {
        return db.sql("""
                        INSERT INTO sp_user_profile (user_id, avg_order_amount, order_count, last_active_at)
                        VALUES (:userId, :avg, :cnt, NOW())
                        ON DUPLICATE KEY UPDATE
                            avg_order_amount = :avg,
                            order_count = :cnt,
                            last_active_at = NOW()
                        """)
                .bind("userId", userId)
                .bind("avg", avgAmount)
                .bind("cnt", orderCount)
                .fetch().rowsUpdated();
    }

    /** 供「猜你喜欢」用：用户买过的品类 ID */
    public Mono<List<Long>> findPurchasedCategoryIds(long userId, int limit) {
        return db.sql("""
                        SELECT p.category_id, COUNT(*) AS cnt
                        FROM sp_order o
                        JOIN sp_order_item i ON i.order_id = o.id
                        JOIN sp_product p ON p.id = i.product_id
                        WHERE o.user_id = :userId AND o.status IN (1,2,3)
                        GROUP BY p.category_id
                        ORDER BY cnt DESC
                        LIMIT :limit
                        """)
                .bind("userId", userId)
                .bind("limit", limit)
                .map((row, meta) -> row.get("category_id", Long.class))
                .all()
                .collectList();
    }

    /** 便于排查：直接看某用户最近一条订单的原始行 */
    public Mono<Map<String, Object>> debugLatestOrder(long userId) {
        return db.sql("SELECT * FROM sp_order WHERE user_id = :userId ORDER BY id DESC LIMIT 1")
                .bind("userId", userId)
                .map(RowMappers::toMap).one()
                .defaultIfEmpty(new LinkedHashMap<>());
    }
}
