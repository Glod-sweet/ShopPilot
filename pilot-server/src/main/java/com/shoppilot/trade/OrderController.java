package com.shoppilot.trade;

import com.shoppilot.common.ApiResponse;
import com.shoppilot.common.PageResult;
import com.shoppilot.common.TraceIdFilter;
import com.shoppilot.security.AuthWebFilter;
import com.shoppilot.trade.dto.CreateOrderRequest;
import com.shoppilot.trade.dto.OrderView;
import com.shoppilot.trade.service.OrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 订单接口。
 * <p>
 * 注意这里没有「修改金额」「修改价格」之类的接口——金额一律服务端算，
 * 前端只传要结算哪些条目。
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /** 下单：扣库存 + 写订单 + 清购物车，一个事务 */
    @PostMapping
    public Mono<ApiResponse<OrderView>> create(@RequestBody(required = false) CreateOrderRequest req,
                                               ServerWebExchange exchange) {
        long userId = AuthWebFilter.currentUser(exchange).userId();
        CreateOrderRequest body = req == null
                ? new CreateOrderRequest(null, null, null) : req;
        return orderService.create(userId, body)
                .map(v -> ApiResponse.ok(v, TraceIdFilter.current(exchange)));
    }

    @GetMapping
    public Mono<ApiResponse<PageResult<OrderView>>> list(
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            ServerWebExchange exchange) {
        long userId = AuthWebFilter.currentUser(exchange).userId();
        return orderService.page(userId, status, page, size)
                .map(r -> ApiResponse.ok(r, TraceIdFilter.current(exchange)));
    }

    @GetMapping("/{id}")
    public Mono<ApiResponse<OrderView>> detail(@PathVariable long id, ServerWebExchange exchange) {
        long userId = AuthWebFilter.currentUser(exchange).userId();
        return orderService.detail(userId, id)
                .map(v -> ApiResponse.ok(v, TraceIdFilter.current(exchange)));
    }

    @PostMapping("/{id}/cancel")
    public Mono<ApiResponse<OrderView>> cancel(@PathVariable long id, ServerWebExchange exchange) {
        long userId = AuthWebFilter.currentUser(exchange).userId();
        return orderService.cancel(userId, id)
                .map(v -> ApiResponse.ok(v, TraceIdFilter.current(exchange)));
    }

    /**
     * 支付占位接口。
     * 本项目「明确不做支付渠道对接」（见 README），这里只把状态推到已付款，
     * 用于打通「下单 → 支付 → 再来一单」的完整链路演示。
     */
    @PostMapping("/{id}/pay")
    public Mono<ApiResponse<OrderView>> pay(@PathVariable long id, ServerWebExchange exchange) {
        long userId = AuthWebFilter.currentUser(exchange).userId();
        return orderService.pay(userId, id)
                .map(v -> ApiResponse.ok(v, TraceIdFilter.current(exchange)));
    }
}
