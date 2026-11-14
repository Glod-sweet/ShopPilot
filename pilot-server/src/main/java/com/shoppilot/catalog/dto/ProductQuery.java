package com.shoppilot.catalog.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 商品列表查询条件。
 * 全部字段可选：不传就是「全部在售商品按销量排序」。
 */
public record ProductQuery(
        Long categoryId,
        Long brandId,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        String keyword,
        /** 排序：sales / price_asc / price_desc / rating / newest */
        String sort,
        int page,
        int size,
        /** 是否包含子品类（查一级品类时带上它的二级） */
        boolean includeChildren
) {

    public ProductQuery {
        page = Math.max(page, 1);
        size = size <= 0 ? 20 : Math.min(size, 100);
        sort = (sort == null || sort.isBlank()) ? "sales" : sort;
    }

    public static ProductQuery defaults() {
        return new ProductQuery(null, null, null, null, null, "sales", 1, 20, true);
    }

    /** 白名单式排序，绝不把前端传的字符串直接拼进 SQL */
    public String orderByClause() {
        return switch (sort) {
            case "price_asc" -> "p.price ASC, p.id DESC";
            case "price_desc" -> "p.price DESC, p.id DESC";
            case "rating" -> "p.rating DESC, p.sales_count DESC";
            case "newest" -> "p.created_at DESC, p.id DESC";
            default -> "p.sales_count DESC, p.rating DESC";
        };
    }

    public int offset() {
        return (page - 1) * size;
    }

    /** 用于日志与埋点，避免把密码之类的敏感字段带出去（本对象无敏感字段） */
    public List<Object> fingerprint() {
        return List.of(categoryId == null ? "-" : categoryId,
                brandId == null ? "-" : brandId,
                minPrice == null ? "-" : minPrice,
                maxPrice == null ? "-" : maxPrice,
                keyword == null ? "-" : keyword,
                sort, page, size);
    }
}
