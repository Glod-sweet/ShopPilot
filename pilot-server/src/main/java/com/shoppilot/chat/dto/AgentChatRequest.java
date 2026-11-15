package com.shoppilot.chat.dto;

import java.util.List;

/**
 * 传给 Python 的内部请求。history 与 slots 由 Java 从库中读取，
 * Python 无状态，不查库。
 */
public record AgentChatRequest(
        Long userId,
        String sessionId,
        String message,
        List<Message> history,
        Slots slots,
        String traceId,
        /** 多模态：base64 / data URL / http 图片地址。null 表示纯文本请求 */
        String image
) {

    /** 兼容不带图片的调用 */
    public AgentChatRequest(Long userId, String sessionId, String message,
                            List<Message> history, Slots slots, String traceId) {
        this(userId, sessionId, message, history, slots, traceId, null);
    }

    public record Message(String role, String content) {
    }

    /**
     * 槽位状态。null 表示该槽位尚未收集，Python 侧据此决定是否追问。
     */
    public record Slots(String category, String budget, String scene) {
    }
}
