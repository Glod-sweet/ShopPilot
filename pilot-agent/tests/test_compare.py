"""商品对比测试。

测的是 _format_comparison 的输出结构 —— 这段文本是模型做比较的唯一依据，
格式错了模型就会漏项或瞎编。所以断言要具体到「每个关键事实是否都在」。
"""

from __future__ import annotations

import pytest

from app.tools import _format_comparison, compare_products


def _product(pid: int, title: str, price: float, **kwargs) -> dict:
    base = {
        "id": pid,
        "title": title,
        "price": price,
        "sub_title": kwargs.get("sub_title", ""),
        "stock": kwargs.get("stock", 10),
        "rating": kwargs.get("rating", 4.5),
        "sales_count": kwargs.get("sales_count", 100),
        "tags": kwargs.get("tags", []),
        "params": kwargs.get("params"),
        "description": kwargs.get("description"),
    }
    return base


class TestFormatComparison:
    def test_includes_all_products(self) -> None:
        details = [
            _product(1, "声浪3 耳机", 299),
            _product(2, "声浪4 耳机", 449),
        ]
        text = _format_comparison(details, [])
        assert "声浪3 耳机" in text
        assert "声浪4 耳机" in text
        assert "共 2 件商品" in text

    def test_includes_prices(self) -> None:
        """价格必须给到，否则模型会瞎猜性价比"""
        details = [_product(1, "A", 299), _product(2, "B", 449)]
        text = _format_comparison(details, [])
        assert "299" in text
        assert "449" in text

    def test_includes_params(self) -> None:
        """参数是质量对比的依据"""
        details = [
            _product(1, "A", 299, params={"降噪深度": "42dB", "续航": "10小时"}),
            _product(2, "B", 449, params={"降噪深度": "48dB", "续航": "12小时"}),
        ]
        text = _format_comparison(details, [])
        assert "42dB" in text
        assert "48dB" in text

    def test_includes_three_dimensions(self) -> None:
        """三个对比维度必须显式提示，否则模型容易只罗列参数"""
        details = [_product(1, "A", 100), _product(2, "B", 200)]
        text = _format_comparison(details, [])
        assert "质量" in text
        assert "性价比" in text
        assert "适配场景" in text

    def test_requires_explicit_conclusion(self) -> None:
        """必须要求模型给出明确推荐，否则会变成「都不错看需求」的和稀泥"""
        details = [_product(1, "A", 100), _product(2, "B", 200)]
        text = _format_comparison(details, [])
        assert "明确推荐" in text or "推荐一件" in text

    def test_stock_status_readable(self) -> None:
        """库存要转成「有货/无货」，直接给数字模型不一定理解含义"""
        details = [
            _product(1, "A", 100, stock=0),
            _product(2, "B", 200, stock=50),
        ]
        text = _format_comparison(details, [])
        assert "无货" in text
        assert "有货" in text

    def test_failed_ids_warned(self) -> None:
        """取不到的商品必须明确告知模型不要评价，否则会编造"""
        details = [_product(1, "A", 100), _product(2, "B", 200)]
        text = _format_comparison(details, failed=[999])
        assert "999" in text
        assert "不要" in text

    def test_no_failed_no_warning(self) -> None:
        details = [_product(1, "A", 100), _product(2, "B", 200)]
        text = _format_comparison(details, failed=[])
        assert "取不到" not in text

    def test_tags_included(self) -> None:
        details = [
            _product(1, "A", 100, tags=["礼盒包装", "顺丰包邮"]),
            _product(2, "B", 200, tags=["可刻字"]),
        ]
        text = _format_comparison(details, [])
        assert "礼盒包装" in text
        assert "可刻字" in text

    def test_handles_missing_optional_fields(self) -> None:
        """缺参数/描述/标签时不能崩 —— 数据不全很常见"""
        details = [
            {"id": 1, "title": "A", "price": 100},
            {"id": 2, "title": "B", "price": 200},
        ]
        text = _format_comparison(details, [])
        assert "A" in text
        assert "B" in text

    def test_params_as_list_does_not_crash(self) -> None:
        """params 有时是 list 而不是 dict，不能假定类型"""
        details = [
            _product(1, "A", 100, params=[{"k": "v"}]),
            _product(2, "B", 200, params={"k": "v"}),
        ]
        text = _format_comparison(details, [])
        assert "A" in text


class TestCompareToolGuards:
    @pytest.mark.asyncio
    async def test_single_product_rejected(self) -> None:
        """只传 1 件时应引导去用详情工具，而不是硬做对比"""
        result = await compare_products.ainvoke({"product_ids": [1]})
        assert "至少需要 2 件" in result

    @pytest.mark.asyncio
    async def test_empty_rejected(self) -> None:
        result = await compare_products.ainvoke({"product_ids": []})
        assert "至少需要 2 件" in result

    @pytest.mark.asyncio
    async def test_truncates_to_five(self) -> None:
        """超过 5 件要截断，不能把 10 件全丢给模型"""
        # 这些 ID 都不存在，会全部失败，返回「取不到」而不是崩
        result = await compare_products.ainvoke({"product_ids": list(range(1, 11))})
        assert isinstance(result, str)
        assert len(result) > 0
