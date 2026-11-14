package com.shoppilot.trade.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单视图。
 */
public record OrderView(
        Long orderId,
        String orderNo,
        BigDecimal totalAmount,
        BigDecimal payAmount,
        Integer status,
        String statusText,
        String source,
        LocalDateTime createdAt,
        LocalDateTime paidAt,
        List<Item> items
) {

    public record Item(
            Long productId,
            Long skuId,
            String productTitle,
            String spec,
            BigDecimal price,
            Integer quantity,
            BigDecimal amount
    ) {
    }

    /** 状态码 → 中文。前端不该自己维护这套映射，否则两端容易漂移 */
    public static String statusText(Integer status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case 0 -> "待付款";
            case 1 -> "已付款";
            case 2 -> "已发货";
            case 3 -> "已完成";
            case 4 -> "已取消";
            default -> "未知";
        };
    }
}
