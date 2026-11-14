package com.shoppilot.trade.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.Map;

/**
 * 下单请求。
 * <p>
 * 两种模式：
 *   1. cartItemIds 为空 → 结算购物车中已勾选的条目
 *   2. cartItemIds 非空 → 只结算指定条目（前端勾选后传入）
 * 金额一律服务端重算，请求体里没有金额字段，从根上杜绝改价。
 */
public record CreateOrderRequest(
        List<Long> cartItemIds,

        /** 收货信息，本项目不做履约，仅落库占位 */
        Map<String, String> shipping,

        /** 备注 */
        String remark
) {
}
