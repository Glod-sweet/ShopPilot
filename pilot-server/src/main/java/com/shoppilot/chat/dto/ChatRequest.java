package com.shoppilot.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 前端发起对话的请求体。
 *
 * sessionId 为空表示新建会话，由服务端生成后通过响应头 X-Session-Id 返回。
 * slots 由前端回传：用户在 slot_ask 事件中点击选项后，前端把已收集的槽位带上，
 * 服务端据此决定是否还需要追问。
 * compareProductIds 非空时进入「商品对比」模式：用户已指定要比的对象，
 * Agent 不再重新检索，而是逐件拉详情后横向比较。
 */
public record ChatRequest(
        String sessionId,
        @NotBlank(message = "message 不能为空")
        @Size(max = 2000, message = "message 长度不能超过 2000")
        String message,
        Slots slots,
        /** 参与对比的商品 ID。为空表示普通推荐请求 */
        List<Long> compareProductIds,
        String clientTraceId,
        /** 多模态：base64 / data URL / http 图片地址。null 表示纯文本请求 */
        String image
) {

    /** 对比模式允许的最大商品数。超过这个数模型写不出有信息量的比较 */
    public static final int MAX_COMPARE = 5;

    /** 兼容前端不传图片的调用 */
    public ChatRequest(String sessionId, String message, Slots slots, String clientTraceId) {
        this(sessionId, message, slots, null, clientTraceId, null);
    }

    public record Slots(String category, String budget, String scene) {
        public static Slots empty() {
            return new Slots(null, null, null);
        }
    }

    public Slots slotsOrEmpty() {
        return slots == null ? Slots.empty() : slots;
    }

    /**
     * 归一化后的对比商品 ID。
     * <p>
     * 三件事：去 null、去重、截断到 MAX_COMPARE。
     * 为什么不在这里报错：对比数量超限不是用户错误，是他选了太多，
     * 截断比弹错更符合直觉。但服务端要记日志，否则排查时不知道为什么少了两件。
     */
    public List<Long> compareIdsOrEmpty() {
        if (compareProductIds == null || compareProductIds.isEmpty()) {
            return List.of();
        }
        return compareProductIds.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .limit(MAX_COMPARE)
                .toList();
    }

    /** 是否处于对比模式 */
    public boolean isCompareMode() {
        return !compareIdsOrEmpty().isEmpty();
    }
}
