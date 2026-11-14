package com.shoppilot.catalog.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 商品卡片。字段与 api-contract.md 4.2 的 product_card 严格对齐，
 * 额外带 categoryName / brandName 便于前端展示，不影响契约。
 */
public record ProductCard(
        Long productId,
        String title,
        String subTitle,
        BigDecimal price,
        BigDecimal originalPrice,
        String coverUrl,
        Integer stock,
        Integer salesCount,
        BigDecimal rating,
        List<String> tags,
        String categoryName,
        String brandName,
        String reason
) {
    /** 由数据库行转卡片。reason 为空时用副标题兜底，保证契约里的必填字段不缺失。 */
    public static ProductCard from(Map<String, Object> row) {
        String reason = str(row.get("scene_reason"));
        if (reason == null || reason.isBlank()) {
            reason = str(row.get("sub_title"));
        }
        return new ProductCard(
                toLong(row.get("id")),
                str(row.get("title")),
                str(row.get("sub_title")),
                toDecimal(row.get("price")),
                toDecimal(row.get("original_price")),
                str(row.get("cover_url")),
                toInt(row.get("stock")),
                toInt(row.get("sales_count")),
                toDecimal(row.get("rating")),
                com.shoppilot.common.JsonUtils.toStringList(str(row.get("tags"))),
                str(row.get("category_name")),
                str(row.get("brand_name")),
                reason == null ? "符合您的筛选条件" : reason
        );
    }

    private static Long toLong(Object o) {
        return com.shoppilot.common.RowValues.asLong(o);
    }

    private static Integer toInt(Object o) {
        return com.shoppilot.common.RowValues.asIntOrNull(o);
    }

    private static BigDecimal toDecimal(Object o) {
        return com.shoppilot.common.RowValues.asDecimal(o);
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
