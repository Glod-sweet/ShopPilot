package com.shoppilot.catalog;

import com.shoppilot.catalog.dto.CategoryNode;
import com.shoppilot.catalog.dto.ProductCard;
import com.shoppilot.catalog.dto.ProductDetail;
import com.shoppilot.catalog.dto.ProductQuery;
import com.shoppilot.catalog.service.CatalogService;
import com.shoppilot.common.ApiResponse;
import com.shoppilot.common.PageResult;
import com.shoppilot.common.TraceIdFilter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 商品浏览接口（人找货路径）。
 * <p>
 * 与 /api/chat/stream 并列：不经过 Agent，纯结构化查询。
 * 前端在「分类逛」和「搜索结果页」用它，Agent 在对话里用内部工具，
 * 两条路径共享同一份商品数据但互不依赖。
 */
@RestController
@RequestMapping("/api")
public class ProductController {

    private final CatalogService catalogService;

    public ProductController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping("/products")
    public Mono<ApiResponse<PageResult<ProductCard>>> list(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long brandId,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "sales") String sort,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "true") boolean includeChildren,
            ServerWebExchange exchange) {

        ProductQuery q = new ProductQuery(categoryId, brandId, minPrice, maxPrice,
                keyword, sort, page, size, includeChildren);
        return catalogService.page(q)
                .map(r -> ApiResponse.ok(r, TraceIdFilter.current(exchange)));
    }

    @GetMapping("/products/{id}")
    public Mono<ApiResponse<ProductDetail>> detail(@PathVariable long id, ServerWebExchange exchange) {
        return catalogService.detail(id)
                .map(d -> ApiResponse.ok(d, TraceIdFilter.current(exchange)));
    }

    @GetMapping("/categories")
    public Mono<ApiResponse<List<CategoryNode>>> categories(ServerWebExchange exchange) {
        return catalogService.categoryTree()
                .map(c -> ApiResponse.ok(c, TraceIdFilter.current(exchange)));
    }

    @GetMapping("/brands")
    public Mono<ApiResponse<List<Map<String, Object>>>> brands(ServerWebExchange exchange) {
        return catalogService.brands()
                .map(b -> ApiResponse.ok(b, TraceIdFilter.current(exchange)));
    }

    @GetMapping("/scenes")
    public Mono<ApiResponse<List<Map<String, Object>>>> scenes(ServerWebExchange exchange) {
        return catalogService.scenes()
                .map(s -> ApiResponse.ok(s, TraceIdFilter.current(exchange)));
    }
}
