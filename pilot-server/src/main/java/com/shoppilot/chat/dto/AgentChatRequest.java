package com.shoppilot.chat.dto;

import java.util.List;
import java.util.Map;

/**
 * 传给 Python 的内部请求（api-contract.md 第 5 节）。
 * history 与 slots 由 Java 从库中读取，Python 无状态，不查库。
 */
public record AgentChatRequest(
        Long userId,
        String sessionId,
        String message,
        List<Map<String, String>> history,
        Slots slots,
        String traceId
) {

    public record Message(String role, String content) {
    }

    /**
     * 槽位状态。null 表示该槽位尚未收集，Python 侧据此决定是否追问。
     * budget 用字符串而非数字：用户说法多样（「300以内」「100-300」），
     * 由 Java 归一化成人类可读的表达，Python 不需要理解金额语义。
     */
    public record Slots(String category, String budget, String scene) {
    }
}
