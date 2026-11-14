package com.shoppilot.catalog.dto;

import java.util.List;

/**
 * 品类树。前端左侧导航用。
 * 只有两级（一级 1~99 / 二级 101+），不做无限层级——电商品类超过两层
 * 对导购场景没有收益，反而让 Agent 的意图映射变复杂。
 */
public record CategoryNode(
        Long id,
        String name,
        Long parentId,
        Integer level,
        Integer sort,
        List<CategoryNode> children
) {
}
