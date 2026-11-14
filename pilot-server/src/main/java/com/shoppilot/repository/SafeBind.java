package com.shoppilot.repository;

import org.springframework.r2dbc.core.DatabaseClient;

/**
 * R2DBC 参数安全绑定。
 *
 * 为什么需要：{@code DatabaseClient.bind(name, null)} 会直接抛
 * "Value for parameter xxx must not be null. Use bindNull(…) instead."，
 * 而不是把 NULL 传给数据库。埋点、日志这类字段大量可空（traceId 可能来自
 * 未经 TraceIdFilter 的内部调用、sessionId 可能为 null），逐个写三元判断
 * 既啰嗦又容易漏。
 *
 * 用法：
 * <pre>
 *   var spec = SafeBind.of(db.sql("INSERT ..."))
 *       .bind("traceId", traceId)      // 为 null 时自动走 bindNull
 *       .bind("count", 10)
 *       .spec();
 * </pre>
 */
public final class SafeBind {

    private final DatabaseClient.GenericExecuteSpec spec;

    private SafeBind(DatabaseClient.GenericExecuteSpec spec) {
        this.spec = spec;
    }

    public static SafeBind of(DatabaseClient.GenericExecuteSpec spec) {
        return new SafeBind(spec);
    }

    /**
     * 绑定参数；value 为 null 时按 Object 类型 bindNull。
     * 对 MySQL 驱动来说 bindNull(..., Object.class) 是可接受的，
     * 少数需要精确类型的场景（如 JSON 列）请在调用方显式处理。
     */
    public SafeBind bind(String name, Object value) {
        return new SafeBind(value == null
                ? spec.bindNull(name, Object.class)
                : spec.bind(name, value));
    }

    public SafeBind bind(String name, String value) {
        return new SafeBind(value == null
                ? spec.bindNull(name, String.class)
                : spec.bind(name, value));
    }

    public SafeBind bind(String name, Long value) {
        return new SafeBind(value == null
                ? spec.bindNull(name, Long.class)
                : spec.bind(name, value));
    }

    public SafeBind bind(String name, Integer value) {
        return new SafeBind(value == null
                ? spec.bindNull(name, Integer.class)
                : spec.bind(name, value));
    }

    /** 金额列大量使用 BigDecimal，缺这个重载会导致调用落到 Object 版本，JSON 列会写错类型 */
    public SafeBind bind(String name, java.math.BigDecimal value) {
        return new SafeBind(value == null
                ? spec.bindNull(name, java.math.BigDecimal.class)
                : spec.bind(name, value));
    }

    /** 基本类型：非空语义，直接绑定 */
    public SafeBind bind(String name, int value) {
        return new SafeBind(spec.bind(name, value));
    }

    public SafeBind bind(String name, long value) {
        return new SafeBind(spec.bind(name, value));
    }

    public SafeBind bind(String name, boolean value) {
        return new SafeBind(spec.bind(name, value));
    }

    public DatabaseClient.GenericExecuteSpec spec() {
        return spec;
    }
}
