package com.shoppilot.trade.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * 加入购物车请求。
 * <p>
 * 两个入口共用它：
 *   1. 前端直接加购：POST /api/cart/items（走 JWT 鉴权）
 *   2. Agent 建议后用户确认：POST /api/cart/confirm（同样走 JWT，不经 Agent）
 * 这是 api-contract.md 第 7 节的落地——写操作始终由用户发起。
 */
public record AddCartRequest(
        @NotNull(message = "productId 不能为空")
        Long productId,

        /** 可空：单规格商品不需要选 SKU */
        Long skuId,

        @Min(value = 1, message = "数量至少为 1")
        @Max(value = 99, message = "单次最多加购 99 件")
        Integer quantity,

        /** 由哪次对话促成，用于归因统计；前端直加时为空 */
        String sessionId,

        /** Agent 传过来的上下文，仅记录不参与业务判断 */
        Map<String, Object> agentContext
) {

    public int quantityOrDefault() {
        return quantity == null ? 1 : quantity;
    }
}
