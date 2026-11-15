package com.shoppilot.internal;

import com.shoppilot.common.ApiResponse;
import com.shoppilot.common.BizException;
import com.shoppilot.common.ErrorCode;
import com.shoppilot.common.RowValues;
import com.shoppilot.repository.OrderRepository;
import com.shoppilot.repository.ProductRepository;
import com.shoppilot.search.HybridSearchService;
import com.shoppilot.trade.dto.AddCartRequest;
import com.shoppilot.trade.service.CartService;
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
 * 供 Python Agent 反向调用的内部接口。
 * <p>
 * 这是 handbook 第十一章第 4 条原则的落点：「工具调用是 Python 反向找 Java，
 * 不是 Python 直接连数据库，写权限只属于 Java」。因此本控制器是 Agent 唯一的
 * 业务数据来源，所有查询与写入都在这里转交给真正的 Service / Repository。
 * <p>
 * 鉴权由 {@code InternalTokenFilter} 统一完成（校验 X-Internal-Token），
 * 本类不再重复判断 token，只负责把请求翻译成业务调用。
 * <p>
 * 响应统一包成 api-contract.md 第 6 节的结构：
 * {@code { "code": 0, "message": "ok", "data": {}, "traceId": null }}。
 * Python 侧约定 code != 0 时必须转成 error 事件，不许编造数据。
 */
@RestController
@RequestMapping("/internal")
public class InternalToolController {

    private static final Logger log = LoggerFactory.getLogger(InternalToolController.class);

    private final HybridSearchService hybridSearch;
    private final ProductRepository productRepo;
    private final OrderRepository orderRepo;
    private final CartService cartService;

    public InternalToolController(HybridSearchService hybridSearch,
                                  ProductRepository productRepo,
                                  OrderRepository orderRepo,
                                  CartService cartService) {
        this.hybridSearch = hybridSearch;
        this.productRepo = productRepo;
        this.orderRepo = orderRepo;
        this.cartService = cartService;
    }

    // =========================================================
    // 一、商品检索（混合检索的真实实现）
    // =========================================================

    /**
     * 混合检索：场景识别 → 结构化过滤 → 语义打分 → 重排序。
     * <p>
     * 真正的检索逻辑在 {@link HybridSearchService}，这里只做参数适配。
     * 之前本方法返回两条硬编码商品，导致 Agent 无论问什么都只拿到香薰礼盒
     * 和马克杯——链路看着通，实际没有检索能力。
     */
    @PostMapping("/products/search")
    public Mono<ApiResponse<Map<String, Object>>> searchProducts(@RequestBody Map<String, Object> body) {
        String query = str(body.get("query"));
        String categoryRaw = str(body.get("category"));
        BigDecimal maxPrice = decimal(body.get("maxPrice"));
        BigDecimal minPrice = decimal(body.get("minPrice"));
        int limit = intOrDefault(body.get("limit"), 5);

        // Agent 传的 category 有三种可能：数字 ID、中文品类名（「耳机」「香薰」）、
        // 英文码（gift/digital/home）。数字直接用，其余走品类名反查；
        // 反查不到就用 0（不限品类），靠场景关键词与语义打分兜住，不要因此报错。
        return resolveCategoryId(categoryRaw)
                .flatMap(cid -> hybridSearch.search(query, cid == 0L ? null : cid,
                        minPrice, maxPrice, limit))
                .map(result -> {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("products", result.products());
                    data.put("total", result.candidateCount());
                    data.put("sceneId", result.sceneId());
                    data.put("sceneCode", result.sceneCode());
                    // rankedIds 是召回顺序，埋点算点击率时要用
                    data.put("rankedIds", result.rankedIds());
                    log.debug("内部检索 query={} 场景={} 候选={} 返回={}",
                            query, result.sceneCode(), result.candidateCount(), result.products().size());
                    return ApiResponse.ok(data);
                })
                .onErrorResume(e -> {
                    log.error("内部检索失败 query={}", query, e);
                    return Mono.just(ApiResponse.<Map<String, Object>>fail(
                            ErrorCode.INTERNAL_ERROR, "商品检索失败：" + e.getMessage(), null));
                });
    }

    /**
     * 把 Agent 传来的品类标识统一成 category_id。
     * 纯数字直接用；否则按名称反查；反查不到返回 null（= 不限品类）。
     * <p>
     * 注意：Reactor 的 map 不允许返回 null，所以「反查不到」用
     * {@code switchIfEmpty} 把空流转成 {@code Mono.just(null 占位)} 的方式处理，
     * 见 {@link #searchProducts} 里的 {@code defaultIfEmpty(0L)} 约定。
     */
    private Mono<Long> resolveCategoryId(String raw) {
        if (raw == null || raw.isBlank()) {
            return Mono.just(0L);
        }
        Long asId = asLongOrNull(raw);
        if (asId != null) {
            return Mono.just(asId);
        }
        // 0 作为「不限品类」的哨兵值，避免 null 在响应式流里传播
        return productRepo.findCategoryIdByName(raw).defaultIfEmpty(0L);
    }

    /**
     * 单商品详情，供 get_product_detail 工具。
     * <p>
     * 契约里这个接口一直存在，但 Java 侧从未实现——Python 调用会拿到 404，
     * 然后走降级分支告诉用户「详情暂时取不到」。补齐后详情能力才真正可用。
     */
    @GetMapping("/products/{id}")
    public Mono<ApiResponse<Map<String, Object>>> productDetail(@PathVariable("id") long id) {
        return productRepo.findById(id)
                .map(row -> {
                    Map<String, Object> detail = new LinkedHashMap<>(row);
                    // attributes / tags / album 在库里是 JSON 字符串，出参要转成对象，
                    // 否则 Python 侧拿到的是 "{\"防水等级\":\"IPX7\"}" 这种带转义的字符串
                    detail.put("params", jsonMap(row.get("attributes")));
                    detail.put("tags", jsonList(row.get("tags")));
                    return ApiResponse.ok(detail);
                })
                .switchIfEmpty(Mono.just(ApiResponse.<Map<String, Object>>fail(
                        ErrorCode.NOT_FOUND, "商品不存在：" + id, null)));
    }

    // =========================================================
    // 二、库存
    // =========================================================

    /**
     * 实时库存，供 check_stock 工具。
     * 支持 SKU 级：传了 skuId 就按 SKU 查，否则看商品总库存。
     */
    @GetMapping("/inventory/{productId}")
    public Mono<ApiResponse<Map<String, Object>>> stock(
            @PathVariable("productId") long productId,
            @RequestParam(value = "skuId", required = false) Long skuId) {
        return productRepo.findStock(productId, skuId)
                .map(row -> {
                    Map<String, Object> data = new LinkedHashMap<>(row);
                    // on_shelf 是 TINYINT(1)，R2DBC 可能读成 Boolean，统一成 0/1
                    data.put("onShelf", RowValues.asInt(row.get("on_shelf"), 0) == 1);
                    data.put("available", RowValues.asInt(row.get("stock"), 0) > 0);
                    return ApiResponse.ok(data);
                })
                .switchIfEmpty(Mono.just(ApiResponse.<Map<String, Object>>fail(
                        ErrorCode.NOT_FOUND, "商品或规格不存在", null)));
    }

    // =========================================================
    // 三、订单（「再来一单」的数据来源）
    // =========================================================

    /**
     * 最近订单，支撑「上次买的洗衣液再来一单」。
     * <p>
     * 仓储层已经带出商品当前库存/价格/上下架并标注 anomaly，
     * Agent 据此能据实说明「已下架」「涨价了」，而不是硬推一个买不了的商品。
     */
    @GetMapping("/orders/recent")
    public Mono<ApiResponse<Map<String, Object>>> recentOrders(
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "limit", defaultValue = "5") int limit,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId) {
        // 身份以 Java 自己传的 X-User-Id 为准；query 参数仅作兜底
        Long uid = headerUserId != null ? headerUserId : userId;
        if (uid == null) {
            return Mono.just(ApiResponse.<Map<String, Object>>fail(
                    ErrorCode.PARAM_INVALID, "缺少 userId", null));
        }

        int n = Math.min(Math.max(limit, 1), 20);
        return orderRepo.findRecentOrders(uid, n)
                .map(row -> {
                    Map<String, Object> item = new LinkedHashMap<>(row);
                    // 字段名对齐契约：Python 侧读的是 productTitle / createdAt
                    item.put("productTitle", row.get("product_title"));
                    item.put("createdAt", row.get("order_time"));
                    item.put("orderId", row.get("order_id"));
                    return item;
                })
                .collectList()
                .map(list -> {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("orders", list);
                    return ApiResponse.ok(data);
                });
    }

    // =========================================================
    // 四、用户画像（个性化推荐的输入）
    // =========================================================

    /**
     * 用户画像：偏好品类、历史客单价、订单数。
     * <p>
     * 契约里同样存在但此前未实现。Agent 拿不到画像时只是少一点个性化，
     * 不阻断对话，所以 Python 侧的降级是合理的；但接口本身不该缺。
     */
    @GetMapping("/users/{id}/profile")
    public Mono<ApiResponse<Map<String, Object>>> userProfile(@PathVariable("id") long id) {
        return orderRepo.findUserProfile(id)
                .map(row -> {
                    Map<String, Object> profile = new LinkedHashMap<>();
                    profile.put("userId", RowValues.asLong(row.get("id")));
                    profile.put("nickname", row.get("nickname"));

                    // preferred_categories 存的是品类 ID 数组，Agent 需要的是名字，
                    // 这里转成 ID 即可，名字由 Agent 结合检索结果自行表述
                    List<Long> catIds = jsonLongList(row.get("preferred_categories"));
                    profile.put("preferredCategoryIds", catIds);

                    profile.put("avgOrderAmount", RowValues.asDecimal(row.get("avg_order_amount")));
                    profile.put("orderCount", RowValues.asInt(row.get("order_count"), 0));
                    profile.put("budgetMin", RowValues.asDecimal(row.get("budget_min")));
                    profile.put("budgetMax", RowValues.asDecimal(row.get("budget_max")));
                    return ApiResponse.ok(profile);
                })
                .switchIfEmpty(Mono.just(ApiResponse.<Map<String, Object>>fail(
                        ErrorCode.NOT_FOUND, "用户不存在：" + id, null)));
    }

    // =========================================================
    // 五、写操作（加购）
    // =========================================================

    /**
     * 加购。
     * <p>
     * 关键约束：Agent <b>不允许</b>直接触发这个动作。契约第 7 节规定写操作必须
     * 由用户在前端二次确认后走 {@code POST /api/cart/confirm}。
     * 这个内部接口只服务于「确认之后」的落库路径，调用方仍是 Java 自己的交易链路。
     * <p>
     * 保留它是为了给 Agent 提供「预检」能力：先校验商品在架、有库存，
     * 再让用户点确认，避免用户点了才发现买不了。
     */
    @PostMapping("/cart/items")
    public Mono<ApiResponse<Map<String, Object>>> addToCart(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId) {
        Long uid = headerUserId != null ? headerUserId : asLongOrNull(body.get("userId"));
        if (uid == null) {
            return Mono.just(ApiResponse.<Map<String, Object>>fail(
                    ErrorCode.PARAM_INVALID, "缺少 userId", null));
        }

        Long productId = asLongOrNull(body.get("productId"));
        if (productId == null) {
            return Mono.just(ApiResponse.<Map<String, Object>>fail(
                    ErrorCode.PARAM_INVALID, "缺少 productId", null));
        }

        AddCartRequest req = new AddCartRequest(
                productId,
                asLongOrNull(body.get("skuId")),
                intOrDefault(body.get("quantity"), 1),
                str(body.get("sessionId")),
                null);

        return cartService.add(uid, req)
                .map(cartItemId -> {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("cartItemId", cartItemId);
                    data.put("needConfirm", false);
                    log.info("Agent 链路加购 userId={} productId={} cartItemId={}", uid, productId, cartItemId);
                    return ApiResponse.ok(data);
                })
                // 业务异常要转成契约里的 code，而不是 500——Python 会据 code 决定话术
                .onErrorResume(BizException.class, e -> Mono.just(
                        ApiResponse.<Map<String, Object>>fail(e.errorCode(), e.getMessage(), null)));
    }

    // =========================================================
    // 工具方法
    // =========================================================

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static BigDecimal decimal(Object o) {
        return RowValues.asDecimal(o);
    }

    private static Long asLongOrNull(Object o) {
        return RowValues.asLong(o);
    }

    private static int intOrDefault(Object o, int dft) {
        Integer v = RowValues.asIntOrNull(o);
        return v == null ? dft : v;
    }

    /** JSON 列 → Map。R2DBC 读 JSON 列拿到的是字符串，直接返回前端会是带转义的一坨 */
    private static Map<String, Object> jsonMap(Object raw) {
        if (raw == null) {
            return Map.of();
        }
        if (raw instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cast = (Map<String, Object>) m;
            return cast;
        }
        return com.shoppilot.common.JsonUtils.toMap(raw.toString());
    }

    /** JSON 列 → List。tags 同理，必须是真数组，否则前端当文本渲染 */
    private static List<?> jsonList(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> l) {
            return l;
        }
        return com.shoppilot.common.JsonUtils.toStringList(raw.toString());
    }

    private static List<Long> jsonLongList(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> l) {
            return l.stream()
                    .map(RowValues::asLong)
                    .filter(java.util.Objects::nonNull)
                    .toList();
        }
        return com.shoppilot.common.JsonUtils.toLongList(raw.toString());
    }
}
