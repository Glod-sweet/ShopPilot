package com.shoppilot.common;

import java.math.BigDecimal;

/**
 * 数据库行取值助手。
 * <p>
 * 存在的理由：R2DBC 的 MySQL 驱动默认把 {@code TINYINT(1)} 映射成 {@link Boolean}
 * （对应 JDBC 的 tinyInt1isBit=true）。项目里 on_shelf / selected / status / is_complete
 * 全是 TINYINT(1)，如果只写 {@code instanceof Number} 判断，Boolean 值会一路走 fallback
 * 变成「下架」「未勾选」——这个 bug 已在联调中真实踩到。
 * <p>
 * 修复分两层：
 *   1. 连接层显式 tinyInt1isBit=false（见 R2dbcConfig），从源头统一成数值
 *   2. 本类做兜底，Boolean / Number / String 三种形态都能正确取到 int
 * 两层都做，是因为驱动版本升级或换连接方式时行为可能再变，
 * 而这里的一个判断错误会让「商品已下架」这种业务结论直接错掉。
 */
public final class RowValues {

    private RowValues() {
    }

    /** 取 int。Boolean → 1/0；Number → intValue；String → 解析；其余返回默认值 */
    public static int asInt(Object o, int defaultValue) {
        if (o instanceof Boolean b) {
            return b ? 1 : 0;
        }
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o instanceof String s) {
            String t = s.trim();
            if ("true".equalsIgnoreCase(t)) {
                return 1;
            }
            if ("false".equalsIgnoreCase(t)) {
                return 0;
            }
            try {
                return Integer.parseInt(t);
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    /** 取 Integer，null 保持 null（区分「没有值」与「值为 0」） */
    public static Integer asIntOrNull(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Boolean b) {
            return b ? 1 : 0;
        }
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /** 取布尔：TINYINT(1) 的 1/0 与 Boolean 都能处理 */
    public static boolean asBool(Object o) {
        return asInt(o, 0) == 1;
    }

    public static Long asLong(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        if (o instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    public static BigDecimal asDecimal(Object o) {
        if (o instanceof BigDecimal d) {
            return d;
        }
        if (o instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        if (o instanceof String s && !s.isBlank()) {
            try {
                return new BigDecimal(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    public static String asString(Object o) {
        return o == null ? null : o.toString();
    }
}
