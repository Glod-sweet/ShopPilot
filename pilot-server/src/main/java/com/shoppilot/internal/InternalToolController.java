package com.shoppilot.internal;

import com.shoppilot.repository.CartRepository;
import com.shoppilot.repository.OrderRepository;
import com.shoppilot.repository.ProductRepository;
import com.shoppilot.search.HybridSearchService;
import com.shoppilot.observability.service.ObservabilityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 供 Python Agent 反向调用的内部接口（api-contract.md 第 6 节）。
 *
 * 鉴权由 {@link com.shoppilot.security.InternalTokenFilter} 统一做（校验 X-Internal-Token），
 * 本类不再重复判断，避免两处逻辑漂移。
 *
 * 用户身份只认请求头 X-User-Id（Java 注入的），不信任请求体里的 userId ——
 * 否则 Agent 就能伪造任意用户身份读别人的订单。
 *
 * 数据来源：真实 MySQL（shop_pilot 库），不再是 mock。
 */
@RestController
@RequestMapping("/internal")
public class InternalToolController {

    private static final Logger log = LoggerFactory.getLogger(InternalToolController.class);

    private final HybridSearchService searchService;
    private final ProductRepository productRepo;
    private final OrderRepository orderRepo;
    private final CartRepository cartRepo;
    private final ObservabilityService observability;

    public InternalToolController(HybridSearchService searchService,
                                  ProductRepository productRepo,
                                  OrderRepository orderRepo,
                                  CartRepository cartRepo,
                                  ObservabilityService observability) {
        this.searchService = searchService;
        this.productRepo = productRepo;
        this.orderRepo = orderRepo;
        this.cartRepo = cartRepo;
        this.observability = observability;
    }

    /**
     * 混合检索：场景识别 + 结构化过滤 + 语义重排序。
     * 详见 HybridSearchService 的类注释。
     */
    @PostMapping("/products/search")
    public Mono<Map<String, Object>> searchProducts(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        String query = asString(body.get("query"), "");
        Long category = asLong(body.get("categoryId"), asLong(body.get("category"), null));
        BigDecimal minPrice = asDecimal(body.get("minPrice"));
        BigDecimal maxPrice = asDecimal(body.get("maxPrice"));
        int limit = asInt(body.get("limit"), 5);
        Long sessionId = asLong(body.get("sessionId"), null);

        long started = System.currentTimeMillis();
        return searchService.search(query, category, minPrice, maxPrice, limit)
                .map(result -> {
                    int elapsed = (int) (System.currentTimeMillis() - started);
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("products", result.products());
                    data.put("total", result.candidateCount());
                    data.put("sceneCode", result.sceneCode());
                    data.put("recalledIds", result.rankedIds());
                    log.info("search query={} scene={} 候选={} 返回={} 耗时={}ms",
                            query, result.sceneCode(), result.candidateCount(),
                            result.products().size(), elapsed);

                    // 埋点：召回/展示都记下来，后面算 CTR 与转化率要用
                    observability.logRecommend(traceId, sessionId, userId, query,
                            result.rankedIds(), result.rankedIds(),
                            result.products().stream()
                                    .map(p -> asLong(p.get("productId"), null))
                                    .filter(java.util.Objects::nonNull)
                                    .toList(),
                            elapsed);
                    observability.logToolCall(traceId, sessionId, "search_products", body, 0, elapsed);
                    return ok(data);
                })
                .onErrorResume(ex -> {
                    log.error("检索失败 query={}", query, ex);
                    observability.logToolCall(traceId, sessionId, "search_products", body, 5001,
                            (int) (System.currentTimeMillis() - started));
                    return Mono.just(fail(5001, "商品检索失败：" + ex.getMessage()));                });
    }

    /** 单商品详情，供多品对比场景拉参数 */
    @GetMapping("/products/{id}")
    public Mono<Map<String, Object>> productDetail(@PathVariable long id,
                                                   @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        long started = System.currentTimeMillis();
        return productRepo.findById(id)
                .map(p -> {
                    observability.logToolCall(traceId, null, "get_product_detail",
                            Map.of("productId", id), 0, (int) (System.currentTimeMillis() - started));
                    return ok(p);
                })
                .defaultIfEmpty(fail(4041, "商品不存在：" + id));
    }

    /** check_stock 工具：实时库存（支持 SKU 级） */
    @GetMapping("/inventory/{productId}")
    public Mono<Map<String, Object>> checkStock(@PathVariable long productId,
                                                @RequestParam(required = false) Long skuId) {
        return productRepo.findStock(productId, skuId)
                .map(s -> {
                    int stock = com.shoppilot.common.RowValues.asInt(s.get("stock"), 0);
                    boolean onShelf = truthy(s.get("on_shelf"));
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("productId", productId);
                    data.put("skuId", skuId);
                    data.put("stock", stock);
                    data.put("onShelf", onShelf);
                    data.put("available", stock > 0 && onShelf);
                    return ok(data);
                })
                .defaultIfEmpty(fail(4041, "商品不存在：" + productId));
    }

    /**
     * 最近订单，支撑「再来一单」。
     * 返回里带 anomaly 字段（OFF_SHELF / OUT_OF_STOCK / PRICE_CHANGED），
     * 让 Agent 能据此组织话术而不是编造。
     */
    @GetMapping("/orders/recent")
    public Mono<Map<String, Object>> recentOrders(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false, defaultValue = "10") int limit,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        // 身份以请求头为准：参数里的 userId 只在前端调试时用，
        // 生产环境 Java 一定会在请求头注入，避免 Agent 伪造他人身份
        long uid = headerUserId != null ? headerUserId : (userId == null ? 0L : userId);
        if (uid <= 0L) {
            return Mono.just(fail(4001, "缺少用户身份（X-User-Id）"));
        }
        long started = System.currentTimeMillis();
        return orderRepo.findRecentOrders(uid, limit)
                .collectList()
                .map(orders -> {
                    observability.logToolCall(traceId, null, "get_order_history",
                            Map.of("userId", uid), 0, (int) (System.currentTimeMillis() - started));
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("orders", orders);
                    data.put("total", orders.size());
                    return ok(data);
                });
    }

    /**
     * 写操作：加购。
     * Agent 不允许直接执行，必须经前端二次确认后由 /api/cart/confirm 触发。
     * 该接口当前仅用于内部链路联调。
     */
    @PostMapping("/cart/items")
    public Mono<Map<String, Object>> addToCart(@RequestBody Map<String, Object> body,
                                               @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
                                               @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        // 身份只认请求头，不接受请求体里的 userId
        long userId = headerUserId != null ? headerUserId : 0L;
        long productId = asLong(body.get("productId"), 0L);
        Long skuId = asLong(body.get("skuId"), null);
        int quantity = asInt(body.get("quantity"), 1);
        Long sessionId = asLong(body.get("sessionId"), null);

        if (userId <= 0L) {
            return Mono.just(fail(4001, "缺少用户身份（X-User-Id）"));
        }
        if (productId <= 0) {
            return Mono.just(fail(4002, "productId 必填"));
        }
        if (quantity <= 0 || quantity > 99) {
            return Mono.just(fail(4002, "quantity 必须在 1~99 之间"));
        }

        long started = System.currentTimeMillis();
        // 校验库存：不能让 Agent 把售罄商品加进购物车
        return productRepo.findById(productId)
                .flatMap(p -> {
                    Object stockObj = p.get("stock");
                    int stock = com.shoppilot.common.RowValues.asInt(stockObj, 0);
                    boolean onShelf = truthy(p.get("on_shelf"));
                    if (!onShelf) {
                        return Mono.just(fail(2003, "商品已下架"));
                    }
                    if (stock < quantity) {
                        return Mono.just(fail(2001, "库存不足，当前仅剩 " + stock + " 件"));
                    }
                    // 加购走 CartRepository.upsert：它已处理「同商品同规格累加」与 99 件上限
                    return cartRepo.upsert(userId, productId, skuId, quantity, "agent", sessionId)
                            .map(cartId -> {
                                observability.logToolCall(traceId, sessionId, "add_to_cart", body, 0,
                                        (int) (System.currentTimeMillis() - started));
                                Map<String, Object> data = new LinkedHashMap<>();
                                data.put("cartItemId", cartId);
                                data.put("productId", productId);
                                data.put("quantity", quantity);
                                return ok(data);
                            });
                })
                .defaultIfEmpty(fail(4041, "商品不存在：" + productId));
    }

    /** 用户画像，供个性化推荐 */
    @GetMapping("/users/{id}/profile")
    public Mono<Map<String, Object>> userProfile(@PathVariable long id) {
        return orderRepo.findUserProfile(id)
                .map(p -> {
                    Map<String, Object> data = new LinkedHashMap<>(p);
                    // 画像里的 JSON 字段转成数组，Python 侧不用再解一层字符串
                    data.put("preferredCategories", com.shoppilot.common.JsonUtils
                            .toLongList(asString(p.get("preferred_categories"), null)));
                    data.put("preferredBrands", com.shoppilot.common.JsonUtils
                            .toLongList(asString(p.get("preferred_brands"), null)));
                    return ok(data);
                })
                .defaultIfEmpty(fail(4041, "用户不存在：" + id));
    }

    /** 场景列表，供 Agent 做意图识别时拉取（带缓存） */
    @GetMapping("/scenes")
    public Mono<Map<String, Object>> scenes() {
        return productRepo.findAllScenes()
                .collectList()
                .map(list -> ok(Map.of("scenes", list)));
    }

    /** 场景下的品类权重，用于把抽象意图映射到具体品类 */
    @GetMapping("/scenes/{id}/categories")
    public Mono<Map<String, Object>> sceneCategories(@PathVariable long id) {
        return productRepo.findCategoriesByScene(id)
                .collectList()
                .map(list -> ok(Map.of("categories", list)));
    }

    // ---------- helpers ----------

    private Map<String, Object> ok(Object data) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", 0);
        m.put("message", "ok");
        m.put("data", data);
        return m;
    }

    private Map<String, Object> fail(int code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("message", message);
        m.put("data", null);
        return m;
    }

    private static String asString(Object o, String dft) {
        return o == null ? dft : o.toString();
    }

    private static Long asLong(Object o, Long dft) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        // 空字符串要当成 null，否则前端传 skuId="" 会被当作非法值
        if (o instanceof String s) {
            String t = s.trim();
            if (t.isEmpty() || "null".equalsIgnoreCase(t)) {
                return dft;
            }
            try {
                return Long.parseLong(t);
            } catch (NumberFormatException ignored) {
                return dft;
            }
        }
        return dft;
    }

    /**
     * 注意不能写成 (int) asLong(...)：asLong 返回装箱的 Long，
     * Java 不允许把 Long 直接强转成 int（只能 long→int 的窄化转换）。
     */
    private static int asInt(Object o, int dft) {
        Long v = asLong(o, null);
        return v == null ? dft : v.intValue();
    }

    private static BigDecimal asDecimal(Object o) {
        if (o instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        if (o instanceof String s && !s.isBlank()) {
            try {
                return new BigDecimal(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 把 TINYINT(1) 列判定成布尔。
     *
     * 不能只写 {@code o instanceof Number n && n.intValue() == 1}：
     * MySQL 的 TINYINT(1) 经 R2DBC 驱动可能返回 Boolean（驱动会按 tinyint(1)
     * 自动映射成 bool），此时 instanceof Number 为 false，条件恒为 false，
     * 导致所有商品都被判成「已下架」——加购接口对任何商品都返回 2003。
     * 这里把 Boolean / Number / 字符串三种形态都覆盖。
     */
    private static boolean truthy(Object o) {
        if (o == null) {
            return false;
        }
        if (o instanceof Boolean b) {
            return b;
        }
        if (o instanceof Number n) {
            return n.intValue() == 1;
        }
        String s = o.toString().trim();
        return "1".equals(s) || "true".equalsIgnoreCase(s);
    }
}
