package com.shoppilot.trade.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 购物车视图。前端渲染购物车页、结算页都用它。
 * <p>
 * 设计要点：金额由服务端算，绝不信前端传来的价格——
 * 这是电商最基本的安全边界。
 */
public record CartView(
        List<Item> items,
        Summary summary
) {

    public record Item(
            Long cartItemId,
            Long productId,
            Long skuId,
            String title,
            String subTitle,
            String coverUrl,
            String spec,
            BigDecimal price,
            Integer quantity,
            BigDecimal amount,
            Integer stock,
            Boolean onShelf,
            Boolean selected,
            String source,
            /** 库存不足 / 已下架 时的提示，正常为 null */
            String warning
    ) {
    }

    public record Summary(
            int itemCount,
            int totalQuantity,
            BigDecimal totalAmount,
            BigDecimal payAmount,
            /** 不可结算的条目数（下架/库存不足/未勾选） */
            int invalidCount
    ) {
    }

    public static CartView empty() {
        return new CartView(List.of(), new Summary(0, 0, BigDecimal.ZERO, BigDecimal.ZERO, 0));
    }
}
