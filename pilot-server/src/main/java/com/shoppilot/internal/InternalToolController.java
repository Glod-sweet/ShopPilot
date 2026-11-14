package com.shoppilot.internal;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 供 Python Agent 反向调用的内部接口。
 * 生产环境必须限制在内网 + 校验 X-Internal-Token。
 * 当前为骨架阶段，返回 mock 数据，后续替换为真实商品/订单查询。
 */
@RestController
@RequestMapping("/internal")
public class InternalToolController {

    /**
     * 混合检索：向量召回 + 结构化过滤。
     * 真实实现：先 pgvector 召回 top 50，再按价格/库存/上架过滤，最后重排序。
     */
    @PostMapping("/products/search")
    public Mono<Map<String, Object>> searchProducts(@RequestBody Map<String, Object> body) {
        List<Map<String, Object>> mock = List.of(
                Map.of(
                        "productId", 100234,
                        "title", "木质调香薰礼盒",
                        "subTitle", "含香薰蜡烛与扩香石",
                        "price", new BigDecimal("268.00"),
                        "originalPrice", new BigDecimal("328.00"),
                        "stock", 42,
                        "salesCount", 1820,
                        "rating", 4.8,
                        "tags", List.of("礼物包装", "顺丰包邮")
                ),
                Map.of(
                        "productId", 100567,
                        "title", "手作陶瓷马克杯",
                        "subTitle", "可定制刻字",
                        "price", new BigDecimal("158.00"),
                        "originalPrice", new BigDecimal("158.00"),
                        "stock", 120,
                        "salesCount", 3402,
                        "rating", 4.7,
                        "tags", List.of("可刻字", "礼盒装")
                )
        );

        return Mono.just(ok(Map.of("products", mock, "total", mock.size())));
    }

    @GetMapping("/orders/recent")
    public Mono<Map<String, Object>> recentOrders() {
        return Mono.just(ok(Map.of("orders", List.of())));
    }

    /**
     * 写操作：加购。真实实现必须校验用户归属与库存，并加分布式锁。
     * Agent 不允许直接调用，需前端二次确认后走 /api/cart 接口。
     */
    @PostMapping("/cart/items")
    public Mono<Map<String, Object>> addToCart(@RequestBody Map<String, Object> body) {
        return Mono.just(ok(Map.of("cartItemId", 9001L)));
    }

    private Map<String, Object> ok(Object data) {
        return Map.of("code", 0, "message", "ok", "data", data);
    }
}
