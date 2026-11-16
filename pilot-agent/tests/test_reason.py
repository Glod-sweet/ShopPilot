"""推荐理由生成的 JSON 容错测试。

模型输出的 JSON 从来不是干净的。实测见过的三种污染：
1. 被 ```json ``` 包裹（最常见，即使提示词明确禁止）
2. 前后带一句解释（「好的，以下是理由：」）
3. 包成 {"items": [...]} 而不是裸数组

_extract_json 必须能处理这三种，否则每次都要降级回 Java 兜底文案 ——
那这个功能等于白做。
"""

from __future__ import annotations

import json

from app.reason import _build_user_prompt, _extract_json


class TestExtractJsonClean:
    def test_bare_array(self) -> None:
        text = '[{"id": 1, "reason": "价格合适"}]'
        assert _extract_json(text) == [{"id": 1, "reason": "价格合适"}]

    def test_empty_array(self) -> None:
        assert _extract_json("[]") == []


class TestExtractJsonPolluted:
    def test_markdown_code_block(self) -> None:
        text = '```json\n[{"id": 1, "reason": "带礼盒包装"}]\n```'
        result = _extract_json(text)
        assert len(result) == 1
        assert result[0]["reason"] == "带礼盒包装"

    def test_code_block_without_language(self) -> None:
        text = '```\n[{"id": 2, "reason": "续航够用"}]\n```'
        assert _extract_json(text)[0]["id"] == 2

    def test_leading_explanation(self) -> None:
        text = '好的，以下是推荐理由：\n[{"id": 1, "reason": "送人有面子"}]'
        assert _extract_json(text)[0]["reason"] == "送人有面子"

    def test_trailing_explanation(self) -> None:
        text = '[{"id": 1, "reason": "轻便好带"}]\n希望对你有帮助！'
        assert _extract_json(text)[0]["reason"] == "轻便好带"

    def test_wrapped_in_object(self) -> None:
        text = '{"items": [{"id": 1, "reason": "性价比高"}]}'
        assert _extract_json(text)[0]["reason"] == "性价比高"

    def test_wrapped_with_data_key(self) -> None:
        text = '{"data": [{"id": 3, "reason": "库存充足"}]}'
        assert _extract_json(text)[0]["id"] == 3


class TestExtractJsonFailures:
    """解析失败必须返回空列表，不能抛异常 —— 抛了会让整条推荐挂掉"""

    def test_empty_string(self) -> None:
        assert _extract_json("") == []

    def test_plain_text(self) -> None:
        assert _extract_json("抱歉，我无法完成这个请求") == []

    def test_malformed_json(self) -> None:
        assert _extract_json('[{"id": 1, "reason": 未加引号}]') == []

    def test_json_object_not_array(self) -> None:
        """对象但没有已知的数组键，返回空"""
        assert _extract_json('{"id": 1, "reason": "单条"}') == []

    def test_none_like(self) -> None:
        assert _extract_json("null") == []


class TestBuildUserPrompt:
    def test_includes_query(self) -> None:
        prompt = _build_user_prompt("想买耳机", {}, [])
        assert "想买耳机" in prompt

    def test_includes_slots(self) -> None:
        prompt = _build_user_prompt(
            "想买耳机", {"category": "蓝牙耳机", "budget": "300以内"}, []
        )
        assert "蓝牙耳机" in prompt
        assert "300以内" in prompt

    def test_includes_product_facts(self) -> None:
        products = [
            {
                "productId": 100234,
                "title": "木质调香薰礼盒",
                "price": 268.0,
                "tags": ["礼盒包装", "顺丰包邮"],
                "rating": 4.8,
                "salesCount": 1820,
                "stock": 42,
            }
        ]
        prompt = _build_user_prompt("送女友", {}, products)

        # 事实必须全给到，否则模型会编造
        assert "100234" in prompt
        assert "木质调香薰礼盒" in prompt
        assert "268" in prompt
        assert "礼盒包装" in prompt
        assert "4.8" in prompt
        assert "1820" in prompt

    def test_no_slots_does_not_add_empty_line(self) -> None:
        prompt = _build_user_prompt("随便看看", {}, [])
        assert "已识别需求" not in prompt

    def test_product_count_stated(self) -> None:
        products = [
            {"productId": i, "title": f"商品{i}", "price": 100} for i in range(3)
        ]
        prompt = _build_user_prompt("买点东西", {}, products)
        assert "3 件商品" in prompt


class TestJsonRoundTrip:
    """确保我们能解析自己生成的 JSON —— 防止 _build_user_prompt 和
    _extract_json 之间的格式假设不一致"""

    def test_expected_model_output_format(self) -> None:
        expected = [
            {"id": 100234, "reason": "268元带礼盒和贺卡，送女友不用另外包装"},
            {"id": 100235, "reason": "189元可刻字，纪念意义更强"},
        ]
        raw = json.dumps(expected, ensure_ascii=False)
        parsed = _extract_json(raw)
        assert len(parsed) == 2
        assert parsed[0]["id"] == 100234
