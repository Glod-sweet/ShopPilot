package com.shoppilot.repository;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 会话域仓储：会话、消息、槽位。
 * <p>
 * 为什么槽位要落库：api-contract.md 规定 Python 无状态，
 * 「上一轮收集到哪一步」必须由 Java 保存并在每轮请求里带上。
 * 否则用户点完「300-500」预算，下一轮 Agent 又从零开始追问。
 */
@Repository
public class ChatRepository {

    private final DatabaseClient db;

    public ChatRepository(DatabaseClient db) {
        this.db = db;
    }

    // ---------------- 会话 ----------------

    /** 按对外 sessionNo 查会话（带用户归属校验，防越权读别人的对话） */
    public Mono<Map<String, Object>> findSession(String sessionNo, long userId) {
        return db.sql("""
                        SELECT id, session_no, user_id, title, status, message_count,
                               last_message_at, created_at
                        FROM sp_chat_session
                        WHERE session_no = :sessionNo AND user_id = :userId
                        """)
                .bind("sessionNo", sessionNo)
                .bind("userId", userId)
                .map(RowMappers::toMap).one();
    }

    public Mono<Map<String, Object>> findSessionById(long sessionId) {
        return db.sql("""
                        SELECT id, session_no, user_id, title, status, message_count,
                               last_message_at, created_at
                        FROM sp_chat_session
                        WHERE id = :id
                        """)
                .bind("id", sessionId)
                .map(RowMappers::toMap).one();
    }

    /** 新建会话。title 取首条用户消息，前端会话列表才有东西可显示 */
    public Mono<Long> createSession(String sessionNo, long userId, String title) {
        return db.sql("""
                        INSERT INTO sp_chat_session
                            (session_no, user_id, title, status, message_count, last_message_at)
                        VALUES (:sessionNo, :userId, :title, 1, 0, NOW())
                        """)
                .bind("sessionNo", sessionNo)
                .bind("userId", userId)
                .bind("title", title == null ? "" : title)
                .fetch().rowsUpdated()
                // 不用 LAST_INSERT_ID()：R2DBC 不保证后续语句在同一连接上，
                // 拿到的可能不是本次插入的值。session_no 有唯一索引，回查最稳。
                .then(db.sql("SELECT id FROM sp_chat_session WHERE session_no = :sessionNo")
                        .bind("sessionNo", sessionNo)
                        .map((row, meta) -> row.get("id", Long.class)).one());
    }

    /** 用户会话列表（历史回看） */
    public Flux<Map<String, Object>> listSessions(long userId, int offset, int size) {
        return db.sql("""
                        SELECT id, session_no, title, message_count, last_message_at, created_at
                        FROM sp_chat_session
                        WHERE user_id = :userId
                        ORDER BY last_message_at DESC, id DESC
                        LIMIT :size OFFSET :offset
                        """)
                .bind("userId", userId)
                .bind("size", size)
                .bind("offset", offset)
                .map(RowMappers::toMap).all();
    }

    public Mono<Long> countSessions(long userId) {
        return db.sql("SELECT COUNT(*) AS c FROM sp_chat_session WHERE user_id = :userId")
                .bind("userId", userId)
                .map((row, meta) -> {
                    Long c = row.get("c", Long.class);
                    return c == null ? 0L : c;
                }).one();
    }

    /** 会话有更新时刷新消息数与最后活跃时间，供列表排序 */
    public Mono<Long> touchSession(long sessionId, int addMessages) {
        return db.sql("""
                        UPDATE sp_chat_session
                        SET message_count = message_count + :add, last_message_at = NOW()
                        WHERE id = :id
                        """)
                .bind("add", addMessages)
                .bind("id", sessionId)
                .fetch().rowsUpdated();
    }

    public Mono<Long> updateTitleIfBlank(long sessionId, String title) {
        return db.sql("""
                        UPDATE sp_chat_session SET title = :title
                        WHERE id = :id AND (title IS NULL OR title = '')
                        """)
                .bind("title", title)
                .bind("id", sessionId)
                .fetch().rowsUpdated();
    }

    // ---------------- 消息 ----------------

    /**
     * 最近 N 条历史，按时间正序返回。
     * 实现上是「倒序取 N 条再反转」——直接正序 LIMIT 拿到的是最早的 N 条，
     * 长会话里会把上下文全丢掉。
     */
    public Flux<Map<String, Object>> findRecentMessages(long sessionId, int limit) {
        return db.sql("""
                        SELECT id, message_no, role, content, event_type, payload,
                               tokens, latency_ms, created_at
                        FROM sp_chat_message
                        WHERE session_id = :sessionId
                        ORDER BY id DESC
                        LIMIT :limit
                        """)
                .bind("sessionId", sessionId)
                .bind("limit", limit)
                .map(RowMappers::toMap)
                // 注意：.map() 返回的是 RowsFetchSpec，没有 collectList()，
                // 必须先 .all() 转成 Flux 才能聚合
                .all()
                .collectList()
                .flatMapMany(list -> {
                    java.util.Collections.reverse(list);
                    return Flux.fromIterable(list);
                });
    }

    public Flux<Map<String, Object>> findAllMessages(long sessionId) {
        return db.sql("""
                        SELECT id, message_no, role, content, event_type, payload,
                               tokens, latency_ms, created_at
                        FROM sp_chat_message
                        WHERE session_id = :sessionId
                        ORDER BY id
                        """)
                .bind("sessionId", sessionId)
                .map(RowMappers::toMap).all();
    }

    /** 落库一条消息。流结束后调用，不在流中间写，避免把流拖慢 */
    public Mono<Long> insertMessage(String messageNo, long sessionId, String role,
                                    String content, String eventType, String payload,
                                    int tokens, int latencyMs, String traceId) {
        // eventType / payload / traceId 都可能为 null（纯 token 回复没有 payload），
        // 必须走 SafeBind，否则 bind(null) 会直接抛异常导致落库失败
        return SafeBind.of(db.sql("""
                        INSERT INTO sp_chat_message
                            (message_no, session_id, role, content, event_type, payload,
                             tokens, latency_ms, trace_id)
                        VALUES (:messageNo, :sessionId, :role, :content, :eventType, :payload,
                                :tokens, :latencyMs, :traceId)
                        """))
                .bind("messageNo", messageNo)
                .bind("sessionId", sessionId)
                .bind("role", role)
                .bind("content", content == null ? "" : content)
                .bind("eventType", eventType)
                .bind("payload", payload)
                .bind("tokens", tokens)
                .bind("latencyMs", latencyMs)
                .bind("traceId", traceId)
                .spec()
                .fetch().rowsUpdated()
                // 同上：message_no 唯一，回查比 LAST_INSERT_ID() 可靠
                .then(db.sql("SELECT id FROM sp_chat_message WHERE message_no = :messageNo")
                        .bind("messageNo", messageNo)
                        .map((row, meta) -> row.get("id", Long.class)).one());
    }

    // ---------------- 槽位 ----------------

    public Mono<Map<String, Object>> findSlot(long sessionId) {
        return db.sql("""
                        SELECT session_id, category_id, category_name, budget_min, budget_max,
                               scene_code, brand_id, extra, is_complete, updated_at
                        FROM sp_chat_slot
                        WHERE session_id = :sessionId
                        """)
                .bind("sessionId", sessionId)
                .map(RowMappers::toMap).one();
    }

    /**
     * 保存槽位（存在则覆盖）。
     * is_complete 由必填槽位是否齐备算出：品类 + 预算上限都拿到才算齐。
     * 这条规则写在 Java 侧而不是 Python 侧，因为它是业务规则不是推理。
     */
    public Mono<Long> saveSlot(long sessionId, Long categoryId, String categoryName,
                               java.math.BigDecimal budgetMin, java.math.BigDecimal budgetMax,
                               String sceneCode, Long brandId, String extraJson) {
        boolean complete = (categoryId != null || (categoryName != null && !categoryName.isBlank()))
                && budgetMax != null;

        var spec = db.sql("""
                        INSERT INTO sp_chat_slot
                            (session_id, category_id, category_name, budget_min, budget_max,
                             scene_code, brand_id, extra, is_complete)
                        VALUES (:sessionId, :categoryId, :categoryName, :budgetMin, :budgetMax,
                                :sceneCode, :brandId, :extra, :complete)
                        ON DUPLICATE KEY UPDATE
                            category_id = :categoryId,
                            category_name = :categoryName,
                            budget_min = :budgetMin,
                            budget_max = :budgetMax,
                            scene_code = :sceneCode,
                            brand_id = :brandId,
                            extra = :extra,
                            is_complete = :complete
                        """)
                .bind("sessionId", sessionId)
                .bind("categoryName", categoryName == null ? "" : categoryName)
                .bind("complete", complete ? 1 : 0);

        // 可空列必须走 bindNull：R2DBC 的 bind(key, null) 会直接抛
        // IllegalArgumentException("Value for parameter ... must not be null")，
        // 而不是把 null 传给数据库。这个坑此前让槽位写入静默失败 ——
        // 表现为「用户点了预算选项，下一轮还是被追问预算」。
        spec = sceneCode == null ? spec.bindNull("sceneCode", String.class) : spec.bind("sceneCode", sceneCode);
        spec = extraJson == null ? spec.bindNull("extra", String.class) : spec.bind("extra", extraJson);
        spec = categoryId == null ? spec.bindNull("categoryId", Long.class) : spec.bind("categoryId", categoryId);
        spec = budgetMin == null ? spec.bindNull("budgetMin", java.math.BigDecimal.class) : spec.bind("budgetMin", budgetMin);
        spec = budgetMax == null ? spec.bindNull("budgetMax", java.math.BigDecimal.class) : spec.bind("budgetMax", budgetMax);
        spec = brandId == null ? spec.bindNull("brandId", Long.class) : spec.bind("brandId", brandId);

        return spec.fetch().rowsUpdated();
    }

    /** 会话详情：会话 + 消息 + 槽位，一次给全前端要的东西 */
    public Mono<Map<String, Object>> sessionDetail(String sessionNo, long userId) {
        return findSession(sessionNo, userId)
                .flatMap(session -> {
                    long id = ((Number) session.get("id")).longValue();
                    Mono<java.util.List<Map<String, Object>>> msgs = findAllMessages(id).collectList();
                    Mono<Map<String, Object>> slot = findSlot(id)
                            .defaultIfEmpty(new LinkedHashMap<>());

                    return Mono.zip(msgs, slot).map(t -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("sessionId", session.get("session_no"));
                        m.put("title", session.get("title"));
                        m.put("messageCount", session.get("message_count"));
                        m.put("createdAt", session.get("created_at"));
                        m.put("messages", t.getT1());
                        m.put("slots", t.getT2());
                        return m;
                    });
                });
    }
}
