package com.shoppilot.chat.service;

import com.shoppilot.chat.dto.ChatRequest;
import com.shoppilot.common.JsonUtils;
import com.shoppilot.common.RowValues;
import com.shoppilot.repository.ChatRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 会话服务：Java 侧的有状态部分。
 * <p>
 * 三件事：
 *   1. 会话生命周期（新建 / 列表 / 详情）
 *   2. 每轮对话前准备上下文（历史消息 + 槽位），组装成 Python 需要的请求
 *   3. 流结束后落库（用户消息 + 助手消息 + 更新后的槽位）
 * <p>
 * 为什么落库放在流结束后：流式响应期间每写一次库都会拖慢首 token 延迟，
 * 而消息内容在流结束时才完整。中途失败就不落库，宁可丢一轮也不要落半截。
 */
@Service
public class ChatSessionService {

    private static final Logger log = LoggerFactory.getLogger(ChatSessionService.class);

    /** 传给 Python 的历史条数。太多会挤占 prompt 预算，太少会丢上下文 */
    private static final int HISTORY_LIMIT = 10;

    private final ChatRepository chatRepo;

    public ChatSessionService(ChatRepository chatRepo) {
        this.chatRepo = chatRepo;
    }

    /** 生成对外会话号：s_ + 16 位十六进制，与 api-contract.md 示例一致 */
    public static String newSessionNo() {
        return "s_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    public static String newMessageNo() {
        return "m_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    /**
     * 确保会话存在：传了 sessionNo 就校验归属，没传就新建。
     * 返回的 Map 里同时带 id（库内主键）与 sessionNo（对外编号）。
     */
    public Mono<SessionContext> ensureSession(String sessionNo, long userId, String firstMessage) {
        if (sessionNo == null || sessionNo.isBlank()) {
            String no = newSessionNo();
            return chatRepo.createSession(no, userId, truncate(firstMessage, 60))
                    .map(id -> new SessionContext(id, no, true));
        }
        return chatRepo.findSession(sessionNo, userId)
                .map(s -> new SessionContext(((Number) s.get("id")).longValue(), sessionNo, false))
                // 传了不存在的 sessionNo（比如前端本地存的旧值）就按新建处理，
                // 而不是报错——用户体验上重开一个会话比弹错误更好
                .switchIfEmpty(Mono.defer(() -> chatRepo
                        .createSession(sessionNo, userId, truncate(firstMessage, 60))
                        .map(id -> new SessionContext(id, sessionNo, true))));
    }

    /** 准备发给 Python 的上下文：历史消息 + 槽位 */
    public Mono<AgentContext> prepareContext(long sessionId) {
        Mono<List<Map<String, String>>> history = chatRepo
                .findRecentMessages(sessionId, HISTORY_LIMIT)
                .map(m -> Map.of(
                        "role", String.valueOf(m.get("role")),
                        "content", m.get("content") == null ? "" : String.valueOf(m.get("content"))))
                .collectList();

        Mono<Map<String, Object>> slots = chatRepo.findSlot(sessionId)
                .map(this::toSlotPayload)
                .defaultIfEmpty(emptySlots());

        return Mono.zip(history, slots)
                .map(t -> new AgentContext(t.getT1(), t.getT2()));
    }

    /** 槽位 → Python 需要的精简结构，字段名与 api-contract.md 第 5 节一致 */
    private Map<String, Object> toSlotPayload(Map<String, Object> row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("category", row.get("category_name"));
        BigDecimal min = toDecimal(row.get("budget_min"));
        BigDecimal max = toDecimal(row.get("budget_max"));
        // Python 侧只认一个 budget 字符串，这里把区间压成人类可读的表达
        m.put("budget", formatBudget(min, max));
        m.put("scene", row.get("scene_code"));
        // is_complete 是 TINYINT(1)，R2DBC 可能返回 Boolean，
        // 只判断 Number 会让 complete 恒为 false，槽位永远显示未完成
        m.put("complete", truthy(row.get("is_complete")));
        return m;
    }

    private Map<String, Object> emptySlots() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("category", null);
        m.put("budget", null);
        m.put("scene", null);
        m.put("complete", false);
        return m;
    }

    /**
     * 把前端/Agent 回传的槽位合并进库。
     * <p>
     * 合并而非覆盖：用户这轮只说了预算，品类是上一轮的，不能被 null 冲掉。
     */
    public Mono<Long> mergeSlots(long sessionId, ChatRequest.Slots incoming) {
        if (incoming == null) {
            return Mono.just(0L);
        }
        return chatRepo.findSlot(sessionId)
                .map(existing -> new MergedSlot(
                        RowValues.asLong(existing.get("category_id")),
                        coalesce(incoming.category(), str(existing.get("category_name"))),
                        toDecimal(existing.get("budget_min")),
                        toDecimal(existing.get("budget_max")),
                        coalesce(incoming.scene(), str(existing.get("scene_code"))),
                        RowValues.asLong(existing.get("brand_id"))))
                .defaultIfEmpty(new MergedSlot(null, incoming.category(), null, null, incoming.scene(), null))
                .flatMap(base -> {
                    BigDecimal[] range = parseBudget(incoming.budget());
                    BigDecimal min = range[0] != null ? range[0] : base.budgetMin();
                    BigDecimal max = range[1] != null ? range[1] : base.budgetMax();
                    return chatRepo.saveSlot(sessionId, base.categoryId(), base.categoryName(),
                            min, max, base.sceneCode(), base.brandId(), null);
                });
    }

    /** 流结束后落库：用户消息 + 助手消息 */
    public Mono<Void> persistTurn(long sessionId, String userMessage, String assistantMessage,
                                  String eventType, String payloadJson,
                                  int tokens, int latencyMs, String traceId) {
        Mono<Long> user = chatRepo.insertMessage(newMessageNo(), sessionId, "user",
                userMessage, null, null, 0, 0, traceId);
        Mono<Long> assistant = chatRepo.insertMessage(newMessageNo(), sessionId, "assistant",
                assistantMessage, eventType, payloadJson, tokens, latencyMs, traceId);

        return user.then(assistant)
                .then(chatRepo.touchSession(sessionId, 2))
                .then(chatRepo.updateTitleIfBlank(sessionId, truncate(userMessage, 60)))
                .then()
                .doOnError(e -> log.error("对话落库失败 sessionId={} traceId={}", sessionId, traceId, e))
                .onErrorResume(e -> Mono.empty());
    }

    public Mono<List<Map<String, Object>>> listSessions(long userId, int page, int size) {
        int p = Math.max(page, 1);
        int s = size <= 0 ? 20 : Math.min(size, 50);
        return chatRepo.listSessions(userId, (p - 1) * s, s)
                .map(row -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("sessionId", row.get("session_no"));
                    m.put("title", row.get("title"));
                    m.put("messageCount", row.get("message_count"));
                    m.put("lastMessageAt", row.get("last_message_at"));
                    m.put("createdAt", row.get("created_at"));
                    return m;
                })
                .collectList();
    }

    public Mono<Map<String, Object>> sessionDetail(String sessionNo, long userId) {
        return chatRepo.sessionDetail(sessionNo, userId)
                .map(detail -> {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> msgs = (List<Map<String, Object>>) detail.get("messages");
                    List<Map<String, Object>> shaped = new ArrayList<>();
                    for (Map<String, Object> m : msgs) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("messageId", m.get("message_no"));
                        item.put("role", m.get("role"));
                        item.put("content", m.get("content"));
                        item.put("eventType", m.get("event_type"));
                        // payload 在库里是 JSON 字符串，出参必须是对象，否则前端要二次解析
                        item.put("payload", JsonUtils.toMap(str(m.get("payload"))));
                        item.put("createdAt", m.get("created_at"));
                        shaped.add(item);
                    }
                    detail.put("messages", shaped);
                    return detail;
                });
    }

    /** 预算字符串 → [min, max]。支持「100以内」「100-300」「500以上」三种常见说法 */
    static BigDecimal[] parseBudget(String budget) {
        if (budget == null || budget.isBlank()) {
            return new BigDecimal[]{null, null};
        }
        String s = budget.trim()
                .replace("元", "").replace("块钱", "").replace("块", "")
                .replace("以下", "以内").replace("之内", "以内")
                .replace("~", "-").replace("—", "-").replace("到", "-");

        if (s.contains("以内") || s.contains("以下")) {
            BigDecimal max = extractNumber(s);
            return new BigDecimal[]{null, max};
        }
        if (s.contains("以上") || s.contains("起")) {
            BigDecimal min = extractNumber(s);
            return new BigDecimal[]{min, null};
        }
        if (s.contains("-")) {
            String[] parts = s.split("-", 2);
            return new BigDecimal[]{extractNumber(parts[0]), extractNumber(parts[1])};
        }
        BigDecimal single = extractNumber(s);
        // 只给一个数字时当作预算上限，符合「300 以内」的口语习惯
        return new BigDecimal[]{null, single};
    }

    private static BigDecimal extractNumber(String s) {
        if (s == null) {
            return null;
        }
        var m = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(s);
        if (m.find()) {
            try {
                return new BigDecimal(m.group(1));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static String formatBudget(BigDecimal min, BigDecimal max) {
        if (min == null && max == null) {
            return null;
        }
        if (min == null) {
            return max.stripTrailingZeros().toPlainString() + "以内";
        }
        if (max == null) {
            return min.stripTrailingZeros().toPlainString() + "以上";
        }
        return min.stripTrailingZeros().toPlainString() + "-" + max.stripTrailingZeros().toPlainString();
    }

    private static String coalesce(String incoming, String existing) {
        return incoming != null && !incoming.isBlank() ? incoming : existing;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.strip();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    private static BigDecimal toDecimal(Object o) {
        return RowValues.asDecimal(o);
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    /** 会话上下文：库内主键 + 对外编号 + 是否新建 */
    public record SessionContext(long sessionId, String sessionNo, boolean created) {
    }

    /** 发给 Python 的上下文 */
    public record AgentContext(List<Map<String, String>> history, Map<String, Object> slots) {
    }

    /** 槽位合并的中间态 */
    private record MergedSlot(Long categoryId, String categoryName, BigDecimal budgetMin,
                              BigDecimal budgetMax, String sceneCode, Long brandId) {
    }

    /**
     * TINYINT(1) → boolean。
     * R2DBC 驱动会把 tinyint(1) 映射成 Boolean，此时 instanceof Number 为 false，
     * 直接判断 Number 会让布尔标志恒为 false。
     */
    private static boolean truthy(Object o) {
        if (o == null) {
            return false;
        }
        if (o instanceof Boolean b) {
            return b;
        }
        if (o instanceof Number n) {
            return n.intValue() == 1;
        }
        String s = o.toString().trim();
        return "1".equals(s) || "true".equalsIgnoreCase(s);
    }
}
