package com.shoppilot.catalog.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 商品详情。比卡片多出：图片集、参数、卖点、SKU 列表、所属场景。
 * 对应 Agent 的 get_product_detail 工具，也是「多品对比」的数据来源。
 */
public record ProductDetail(
        Long productId,
        String productNo,
        String title,
        String subTitle,
        BigDecimal price,
        BigDecimal originalPrice,
        String coverUrl,
        List<String> album,
        List<String> tags,
        String sellingPoints,
        String description,
        Map<String, Object> attributes,
        Integer stock,
        Integer salesCount,
        BigDecimal rating,
        Integer commentCount,
        Boolean onShelf,
        Boolean giftWrappable,
        Long categoryId,
        String categoryName,
        Long brandId,
        String brandName,
        List<Sku> skus,
        List<SceneRef> scenes
) {

    public record Sku(
            Long skuId,
            String skuCode,
            Map<String, Object> spec,
            BigDecimal price,
            Integer stock,
            String imageUrl,
            Boolean available
    ) {
    }

    /** 该商品适用的场景及人工标注的推荐理由 */
    public record SceneRef(
            Long sceneId,
            String sceneCode,
            String sceneName,
            BigDecimal score,
            String reason
    ) {
    }
}
