package com.shoppilot.trade.service;

import com.shoppilot.common.BizException;
import com.shoppilot.common.ErrorCode;
import com.shoppilot.common.JsonUtils;
import com.shoppilot.common.PageResult;
import com.shoppilot.common.RowValues;
import com.shoppilot.repository.CartRepository;
import com.shoppilot.repository.OrderRepository;
import com.shoppilot.trade.dto.CreateOrderRequest;
import com.shoppilot.trade.dto.OrderView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 订单服务 —— 本项目唯一需要事务的地方。
 * <p>
 * 下单是一次多表写：扣库存 → 写订单 → 写明细 → 清购物车。
 * 任何一步失败都必须整体回滚，否则会出现「扣了库存但没订单」
 * 或者「有订单但购物车没清」的脏数据。
 * <p>
 * 并发控制用的是「条件 UPDATE 原子扣减」，不是悲观锁也不是分布式锁：
 *   UPDATE sp_product SET stock = stock - n WHERE id = ? AND stock >= n
 * 受影响行数为 0 即代表库存不够，直接抛错回滚。这条 SQL 在 InnoDB 下
 * 会对该行加排他锁，天然串行化，不需要额外机制。
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private static final DateTimeFormatter ORDER_NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final OrderRepository orderRepo;
    private final CartRepository cartRepo;

    public OrderService(OrderRepository orderRepo, CartRepository cartRepo) {
        this.orderRepo = orderRepo;
        this.cartRepo = cartRepo;
    }

    /**
     * 创建订单。
     * <p>
     * 事务边界放在这里而不是 Repository：Repository 只管单表，
     * 跨表的一致性必须由 Service 声明。
     */
    @Transactional
    public Mono<OrderView> create(long userId, CreateOrderRequest req) {
        List<Long> requestedIds = req.cartItemIds() == null ? List.of() : req.cartItemIds();

        return cartRepo.findCheckoutItems(userId, requestedIds)
                .collectList()
                .flatMap(rows -> {
                    if (rows.isEmpty()) {
                        return Mono.<OrderView>error(new BizException(ErrorCode.CART_EMPTY,
                                "没有可结算的商品，请先勾选商品"));
                    }
                    // 下单前逐条校验，把问题条目一次性说清楚，而不是让用户试错
                    validate(rows);

                    BigDecimal total = rows.stream()
                            .map(this::amountOf)
                            .reduce(BigDecimal.ZERO, BigDecimal::add)
                            .setScale(2, RoundingMode.HALF_UP);

                    // 运费策略：满 99 免运费，否则 8 元。集中在这里，便于后续改规则
                    BigDecimal shippingFee = total.compareTo(new BigDecimal("99")) >= 0
                            ? BigDecimal.ZERO : new BigDecimal("8.00");
                    BigDecimal payable = total.add(shippingFee).setScale(2, RoundingMode.HALF_UP);

                    String orderNo = generateOrderNo();

                    // ① 先扣库存（最容易失败的一步，放最前面，失败就不用写后续表）
                    return deductAll(rows)
                            // ② 写订单主表
                            .then(orderRepo.insertOrder(orderNo, userId, total, payable, "web", null))
                            // ③ 写明细（快照标题与规格）
                            .flatMap(orderId -> insertItems(orderId, rows).thenReturn(orderId))
                            // ④ 清掉已结算的购物车条目
                            .flatMap(orderId -> {
                                List<Long> ids = rows.stream()
                                        .map(r -> toLong(r.get("cart_item_id")))
                                        .filter(java.util.Objects::nonNull)
                                        .toList();
                                return cartRepo.clearCheckedOut(userId, ids).thenReturn(orderId);
                            })
                            .flatMap(orderId -> detail(userId, orderId))
                            .doOnSuccess(v -> log.info("下单成功 userId={} orderNo={} amount={}",
                                    userId, v.orderNo(), v.payAmount()));
                });
    }

    /** 校验：下架、售罄、库存不足，三类问题分别给明确提示 */
    private void validate(List<Map<String, Object>> rows) {
        for (Map<String, Object> r : rows) {
            int onShelf = toInt(r.get("on_shelf"), 0);
            if (onShelf != 1) {
                throw new BizException(ErrorCode.PRODUCT_OFF_SHELF,
                        "商品「" + str(r.get("title")) + "」已下架");
            }
            Integer skuStatus = toInt(r.get("sku_status"));
            if (skuStatus != null && skuStatus != 1) {
                throw new BizException(ErrorCode.PRODUCT_OFF_SHELF,
                        "商品「" + str(r.get("title")) + "」的该规格已停售");
            }
            int stock = effectiveStock(r);
            int qty = toInt(r.get("quantity"), 1);
            if (stock <= 0) {
                throw new BizException(ErrorCode.STOCK_NOT_ENOUGH,
                        "商品「" + str(r.get("title")) + "」已售罄");
            }
            if (stock < qty) {
                throw new BizException(ErrorCode.STOCK_NOT_ENOUGH,
                        "商品「" + str(r.get("title")) + "」库存仅剩 " + stock + " 件");
            }
        }
    }

    /**
     * 逐条扣库存。用 concatMap 串行执行而不是 flatMap 并行：
     * 并行扣减同一商品的多个 SKU 可能触发死锁，串行反而更稳，
     * 且购物车条目数通常个位数，串行的性能损失可以忽略。
     */
    private Mono<Void> deductAll(List<Map<String, Object>> rows) {
        return Flux.fromIterable(rows)
                .concatMap(row -> {
                    long productId = toLong(row.get("product_id"));
                    Long skuId = toLong(row.get("sku_id"));
                    int qty = toInt(row.get("quantity"), 1);
                    String title = str(row.get("title"));

                    Mono<Boolean> productDeduct = orderRepo.deductStock(productId, qty);
                    Mono<Boolean> skuDeduct = skuId == null
                            ? Mono.just(true)
                            : orderRepo.deductSkuStock(skuId, qty);

                    return productDeduct
                            .flatMap(ok -> {
                                if (!Boolean.TRUE.equals(ok)) {
                                    return Mono.<Boolean>error(new BizException(
                                            ErrorCode.STOCK_NOT_ENOUGH,
                                            "商品「" + title + "」库存不足，请调整数量"));
                                }
                                return skuDeduct;
                            })
                            .flatMap(ok -> Boolean.TRUE.equals(ok)
                                    ? Mono.empty()
                                    : Mono.<Void>error(new BizException(ErrorCode.STOCK_NOT_ENOUGH,
                                    "商品「" + title + "」的该规格库存不足")));
                })
                .then();
    }

    private Mono<Void> insertItems(long orderId, List<Map<String, Object>> rows) {
        return Flux.fromIterable(rows)
                .concatMap(r -> {
                    long productId = toLong(r.get("product_id"));
                    Long skuId = toLong(r.get("sku_id"));
                    int qty = toInt(r.get("quantity"), 1);
                    BigDecimal price = unitPriceOf(r);
                    BigDecimal amount = price.multiply(BigDecimal.valueOf(qty))
                            .setScale(2, RoundingMode.HALF_UP);

                    // 规格快照：把 JSON 对象原样存进 sp_order_item.sku_spec
                    Object spec = r.get("spec");
                    String specJson = spec == null ? null
                            : (spec instanceof String s ? s : JsonUtils.write(spec));

                    return orderRepo.insertOrderItem(orderId, productId, skuId,
                            str(r.get("title")), specJson, price, qty, amount);
                })
                .then();
    }

    /** 订单列表 */
    public Mono<PageResult<OrderView>> page(long userId, Integer status, int page, int size) {
        int p = Math.max(page, 1);
        int s = size <= 0 ? 10 : Math.min(size, 50);
        int offset = (p - 1) * s;

        return orderRepo.pageOrders(userId, status, offset, s)
                .concatMap(row -> {
                    long orderId = toLong(row.get("id"));
                    return orderRepo.findOrderItems(orderId)
                            .map(i -> new OrderView.Item(
                                    toLong(i.get("product_id")),
                                    toLong(i.get("sku_id")),
                                    str(i.get("product_title")),
                                    str(i.get("sku_spec")),
                                    toDecimal(i.get("price")),
                                    toInt(i.get("quantity")),
                                    toDecimal(i.get("amount"))))
                            .collectList()
                            .map(items -> toView(row, items));
                })
                .collectList()
                .zipWith(orderRepo.countOrders(userId, status))
                .map(t -> PageResult.of(t.getT1(), t.getT2(), p, s));
    }

    /** 订单详情 */
    public Mono<OrderView> detail(long userId, long orderId) {
        return orderRepo.findOrder(orderId, userId)
                .switchIfEmpty(Mono.error(BizException.notFound("订单")))
                .flatMap(row -> orderRepo.findOrderItems(orderId)
                        .map(i -> new OrderView.Item(
                                toLong(i.get("product_id")),
                                toLong(i.get("sku_id")),
                                str(i.get("product_title")),
                                str(i.get("sku_spec")),
                                toDecimal(i.get("price")),
                                toInt(i.get("quantity")),
                                toDecimal(i.get("amount"))))
                        .collectList()
                        .map(items -> toView(row, items)));
    }

    /**
     * 取消订单。只有「待付款」可取消，取消后回补库存。
     * 用 updateStatus 的 fromStatus 条件保证幂等：重复点击只有第一次生效。
     */
    @Transactional
    public Mono<OrderView> cancel(long userId, long orderId) {
        return orderRepo.findOrder(orderId, userId)
                .switchIfEmpty(Mono.error(BizException.notFound("订单")))
                .flatMap(row -> {
                    int status = toInt(row.get("status"), -1);
                    if (status != 0) {
                        return Mono.error(new BizException(ErrorCode.ORDER_STATUS_INVALID,
                                "只有待付款订单可以取消，当前状态：" + OrderView.statusText(status)));
                    }
                    return orderRepo.updateStatus(orderId, userId, 0, 4)
                            .flatMap(ok -> {
                                if (!Boolean.TRUE.equals(ok)) {
                                    return Mono.error(new BizException(ErrorCode.ORDER_STATUS_INVALID,
                                            "订单状态已变更，请刷新后重试"));
                                }
                                // 回补库存：按明细逐条加回
                                return orderRepo.findOrderItems(orderId)
                                        .concatMap(item -> {
                                            long pid = toLong(item.get("product_id"));
                                            Long skuId = toLong(item.get("sku_id"));
                                            int qty = toInt(item.get("quantity"), 0);
                                            Mono<Long> p = orderRepo.restoreStock(pid, qty);
                                            Mono<Long> s = skuId == null ? Mono.just(0L)
                                                    : orderRepo.restoreSkuStock(skuId, qty);
                                            return p.then(s);
                                        })
                                        .then(orderRepo.updateStatus(orderId, userId, 4, 4))
                                        .then(detail(userId, orderId));
                            });
                })
                .doOnSuccess(v -> log.info("订单已取消 userId={} orderId={}", userId, orderId));
    }

    /** 支付占位：本项目不对接支付渠道（见 README「明确不做」），只把状态推到已付款 */
    public Mono<OrderView> pay(long userId, long orderId) {
        return orderRepo.markPaid(orderId, userId)
                .flatMap(ok -> Boolean.TRUE.equals(ok)
                        ? detail(userId, orderId)
                        : Mono.error(new BizException(ErrorCode.ORDER_STATUS_INVALID,
                        "订单状态不允许支付，或已支付")))
                .doOnSuccess(v -> log.info("订单标记支付 userId={} orderId={}", userId, orderId));
    }

    private OrderView toView(Map<String, Object> row, List<OrderView.Item> items) {
        Integer status = toInt(row.get("status"));
        return new OrderView(
                toLong(row.get("id")),
                str(row.get("order_no")),
                toDecimal(row.get("total_amount")),
                toDecimal(row.get("pay_amount")),
                status,
                OrderView.statusText(status),
                str(row.get("source")),
                toLocalDateTime(row.get("created_at")),
                toLocalDateTime(row.get("paid_at")),
                items);
    }

    private BigDecimal unitPriceOf(Map<String, Object> r) {
        BigDecimal skuPrice = toDecimal(r.get("sku_price"));
        return skuPrice != null ? skuPrice : toDecimal(r.get("product_price"));
    }

    private BigDecimal amountOf(Map<String, Object> r) {
        int qty = toInt(r.get("quantity"), 1);
        return unitPriceOf(r).multiply(BigDecimal.valueOf(qty));
    }

    private int effectiveStock(Map<String, Object> r) {
        Integer skuStock = toInt(r.get("sku_stock"));
        if (skuStock != null) {
            return skuStock;
        }
        return toInt(r.get("stock"), 0);
    }

    /**
     * 订单号：时间戳 + 4 位随机。
     * 不追求全局唯一（那是分布式 ID 生成器的事），
     * 靠 sp_order.uk_order_no 唯一索引兜底，冲突时数据库会拒绝。
     */
    private String generateOrderNo() {
        return "SO" + LocalDateTime.now().format(ORDER_NO_FMT)
                + ThreadLocalRandom.current().nextInt(1000, 9999);
    }

    private static LocalDateTime toLocalDateTime(Object o) {
        if (o instanceof LocalDateTime t) {
            return t;
        }
        if (o instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime();
        }
        return null;
    }

    private static Long toLong(Object o) {
        return RowValues.asLong(o);
    }

    private static Integer toInt(Object o) {
        return RowValues.asIntOrNull(o);
    }

    private static int toInt(Object o, int dft) {
        return RowValues.asInt(o, dft);
    }

    private static BigDecimal toDecimal(Object o) {
        return RowValues.asDecimal(o);
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
