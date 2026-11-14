package com.shoppilot.trade;

import com.shoppilot.common.ApiResponse;
import com.shoppilot.common.TraceIdFilter;
import com.shoppilot.security.AuthWebFilter;
import com.shoppilot.trade.dto.AddCartRequest;
import com.shoppilot.trade.dto.CartView;
import com.shoppilot.trade.service.CartService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 购物车接口。全部走用户 JWT 鉴权，userId 从 token 取，不接受请求体传 userId。
 */
@RestController
@RequestMapping("/api/cart")
public class CartController {

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @GetMapping
    public Mono<ApiResponse<CartView>> view(ServerWebExchange exchange) {
        long userId = AuthWebFilter.currentUser(exchange).userId();
        return cartService.view(userId)
                .map(v -> ApiResponse.ok(v, TraceIdFilter.current(exchange)));
    }

    @GetMapping("/count")
    public Mono<ApiResponse<Map<String, Object>>> count(ServerWebExchange exchange) {
        long userId = AuthWebFilter.currentUser(exchange).userId();
        return cartService.count(userId)
                .map(c -> ApiResponse.ok(Map.of("count", c), TraceIdFilter.current(exchange)));
    }

    @PostMapping("/items")
    public Mono<ApiResponse<Map<String, Object>>> add(@Valid @RequestBody AddCartRequest req,
                                                      ServerWebExchange exchange) {
        long userId = AuthWebFilter.currentUser(exchange).userId();
        return cartService.add(userId, req)
                .map(id -> ApiResponse.ok(Map.of("cartItemId", id), TraceIdFilter.current(exchange)));
    }

    /**
     * 写操作确认入口（api-contract.md 第 7 节）。
     * <p>
     * Agent 在对话里只能建议「要不要加购」，用户点确认后前端调这个接口——
     * 走正常用户鉴权，不经过 Agent。这样写操作始终在用户明确授权下发生。
     * 语义上与 /items 相同，单独开一个路径是为了埋点归因：
     * source=agent 的加购都从这条路径进来，能算出「AI 导购带来的加购量」。
     */
    @PostMapping("/confirm")
    public Mono<ApiResponse<Map<String, Object>>> confirm(@Valid @RequestBody AddCartRequest req,
                                                          ServerWebExchange exchange) {
        long userId = AuthWebFilter.currentUser(exchange).userId();
        // 打上 agent 归因标记
        AddCartRequest attributed = new AddCartRequest(
                req.productId(), req.skuId(), req.quantityOrDefault(), req.sessionId(),
                req.agentContext() == null ? Map.of("confirmed", true) : req.agentContext());
        return cartService.add(userId, attributed)
                .map(id -> ApiResponse.ok(Map.of("cartItemId", id, "confirmed", true),
                        TraceIdFilter.current(exchange)));
    }

    @PostMapping("/items/{id}/quantity")
    public Mono<ApiResponse<Void>> updateQuantity(@PathVariable long id,
                                                   @RequestParam int quantity,
                                                   ServerWebExchange exchange) {
        long userId = AuthWebFilter.currentUser(exchange).userId();
        return cartService.updateQuantity(userId, id, quantity)
                .thenReturn(ApiResponse.<Void>ok(null, TraceIdFilter.current(exchange)));
    }

    @PostMapping("/items/{id}/selected")
    public Mono<ApiResponse<Void>> select(@PathVariable long id,
                                          @RequestParam boolean selected,
                                          ServerWebExchange exchange) {
        long userId = AuthWebFilter.currentUser(exchange).userId();
        return cartService.select(userId, id, selected)
                .thenReturn(ApiResponse.<Void>ok(null, TraceIdFilter.current(exchange)));
    }

    @PostMapping("/selected")
    public Mono<ApiResponse<Void>> selectAll(@RequestParam boolean selected,
                                             ServerWebExchange exchange) {
        long userId = AuthWebFilter.currentUser(exchange).userId();
        return cartService.selectAll(userId, selected)
                .thenReturn(ApiResponse.<Void>ok(null, TraceIdFilter.current(exchange)));
    }

    @DeleteMapping("/items/{id}")
    public Mono<ApiResponse<Void>> remove(@PathVariable long id, ServerWebExchange exchange) {
        long userId = AuthWebFilter.currentUser(exchange).userId();
        return cartService.remove(userId, id)
                .thenReturn(ApiResponse.<Void>ok(null, TraceIdFilter.current(exchange)));
    }
}
