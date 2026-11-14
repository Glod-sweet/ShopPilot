package com.shoppilot.common;

import java.util.List;

/**
 * 分页结果。total 单独查一次 count，避免列表接口把全表捞出来。
 */
public record PageResult<T>(List<T> list, long total, int page, int size) {

    public static <T> PageResult<T> of(List<T> list, long total, int page, int size) {
        return new PageResult<>(list, total, page, size);
    }

    public static <T> PageResult<T> empty(int page, int size) {
        return new PageResult<>(List.of(), 0L, page, size);
    }

    public int pages() {
        return size <= 0 ? 0 : (int) ((total + size - 1) / size);
    }
}
