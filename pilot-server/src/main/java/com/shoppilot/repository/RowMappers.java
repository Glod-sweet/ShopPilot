package com.shoppilot.repository;

import io.r2dbc.spi.ColumnMetadata;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * R2DBC Row → Map 转换工具。
 *
 * 为什么不直接 row.forEach：io.r2dbc.spi.Row 不是 java.util.Map，
 * 它只提供 get(String) / get(int)，必须通过 RowMetadata 拿到列名再逐个取值。
 * 多个仓储都要这个转换，抽出来避免重复。
 */
final class RowMappers {

    private RowMappers() {
    }

    /** 转成保持列顺序的 LinkedHashMap，便于日志与 JSON 输出可读 */
    static Map<String, Object> toMap(Row row, RowMetadata meta) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (ColumnMetadata col : meta.getColumnMetadatas()) {
            String name = col.getName();
            m.put(name, row.get(name));
        }
        return m;
    }
}
