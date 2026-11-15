package com.shoppilot.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 前端发起对话的请求体。
 *
 * sessionId 为空表示新建会话，由服务端生成后通过响应头 X-Session-Id 返回。
 * slots 由前端回传：用户在 slot_ask 事件中点击选项后，前端把已收集的槽位带上，
 * 服务端据此决定是否还需要追问。
 */
public record ChatRequest(
        String sessionId,
        @NotBlank(message = "message 不能为空")
        @Size(max = 2000, message = "message 长度不能超过 2000")
        String message,
        Slots slots,
        String clientTraceId,
        /** 多模态：base64 / data URL / http 图片地址。null 表示纯文本请求 */
        String image
) {

    /** 兼容前端不传图片的调用 */
    public ChatRequest(String sessionId, String message, Slots slots, String clientTraceId) {
        this(sessionId, message, slots, clientTraceId, null);
    }

    public record Slots(String category, String budget, String scene) {
        public static Slots empty() {
            return new Slots(null, null, null);
        }
    }

    public Slots slotsOrEmpty() {
        return slots == null ? Slots.empty() : slots;
    }
}
