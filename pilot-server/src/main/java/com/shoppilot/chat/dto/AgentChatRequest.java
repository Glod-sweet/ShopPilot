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
        String traceId
) {

    public record Message(String role, String content) {
    }

    /**
     * 槽位状态。null 表示该槽位尚未收集，Python 侧据此决定是否追问。
     */
    public record Slots(String category, String budget, String scene) {
    }
}
