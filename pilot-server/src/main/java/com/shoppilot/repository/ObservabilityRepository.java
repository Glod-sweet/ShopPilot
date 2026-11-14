package com.shoppilot.repository;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 观测域仓储：推荐埋点、工具调用日志。
 * <p>
 * 这两张表只写不读（除统计视图），所以不做过多的查询方法，
 * 保持轻量，避免每次工具调用都多几次 RTT。
 * <p>
 * 注意所有可空字段都用 {@link SafeBind} 绑定：
 * traceId 在内部调用（未经 TraceIdFilter）时会是 null，
 * 直接用 bind() 会抛 "must not be null"，导致埋点写入失败并让整条检索链路报错。
 */
@Repository
public class ObservabilityRepository {

    private final DatabaseClient db;

    public ObservabilityRepository(DatabaseClient db) {
        this.db = db;
    }

    public Mono<Long> insertRecommendLog(String traceId, Long sessionId, Long userId, String query,
                                         String recalledIds, String filteredIds, String shownIds,
                                         int recallCount, int shownCount, int latencyMs) {
        return SafeBind.of(db.sql("""
                        INSERT INTO sp_recommend_log
                            (trace_id, session_id, user_id, query, recalled_ids, filtered_ids,
                             shown_ids, recall_count, shown_count, latency_ms)
                        VALUES (:traceId, :sessionId, :userId, :query, :recalledIds, :filteredIds,
                                :shownIds, :recallCount, :shownCount, :latencyMs)
                        """))
                .bind("traceId", traceId)
                .bind("sessionId", sessionId)
                .bind("userId", userId)
                .bind("query", query)
                .bind("recalledIds", recalledIds)
                .bind("filteredIds", filteredIds)
                .bind("shownIds", shownIds)
                .bind("recallCount", recallCount)
                .bind("shownCount", shownCount)
                .bind("latencyMs", latencyMs)
                .spec()
                .fetch().rowsUpdated();
    }

    public Mono<Long> insertToolCallLog(String traceId, Long sessionId, String toolName,
                                        String args, int resultCode, int elapsedMs) {
        return SafeBind.of(db.sql("""
                        INSERT INTO sp_tool_call_log
                            (trace_id, session_id, tool_name, args, result_code, elapsed_ms)
                        VALUES (:traceId, :sessionId, :toolName, :args, :resultCode, :elapsedMs)
                        """))
                .bind("traceId", traceId)
                .bind("sessionId", sessionId)
                .bind("toolName", toolName)
                .bind("args", args)
                .bind("resultCode", resultCode)
                .bind("elapsedMs", elapsedMs)
                .spec()
                .fetch().rowsUpdated();
    }

    /**
     * 追加点击记录。
     * MySQL 的 JSON_ARRAY_APPEND 可以直接在库里改，避免「读出来改再写回去」
     * 的并发覆盖问题。
     */
    public Mono<Long> appendClicked(String traceId, String clickedJson) {
        return SafeBind.of(db.sql("""
                        UPDATE sp_recommend_log
                        SET clicked_ids = JSON_MERGE_PRESERVE(
                                COALESCE(clicked_ids, JSON_ARRAY()), CAST(:clicked AS JSON))
                        WHERE trace_id = :traceId
                        ORDER BY id DESC
                        LIMIT 1
                        """))
                .bind("clicked", clickedJson)
                .bind("traceId", traceId)
                .spec()
                .fetch().rowsUpdated();
    }

    /**
     * 效果统计。直接查 01_schema.sql 里定义好的 sp_v_recommend_daily 视图，
     * 不在应用层重复算 CTR/CVR 公式——公式只该有一份。
     */
    public Flux<Map<String, Object>> dailyStats(int days) {
        return db.sql("""
                        SELECT stat_date, request_cnt, shown_cnt, click_cnt, order_cnt,
                               ctr, cvr, avg_latency_ms
                        FROM sp_v_recommend_daily
                        ORDER BY stat_date DESC
                        LIMIT :days
                        """)
                .bind("days", days)
                .map(RowMappers::toMap).all();
    }
}
