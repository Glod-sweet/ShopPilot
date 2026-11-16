"""槽位抽取测试。

为什么优先测这块：槽位抽取是纯规则逻辑，一旦正则写错，表现为
「用户说了预算，Agent 还在追问预算」——这种 bug 不会报错，只会让体验变差，
没有测试根本发现不了。
"""

from __future__ import annotations

import pytest

from app.slots import (
    build_slot_ask,
    extract_budget,
    extract_category,
    extract_scene,
    fill_slots,
    is_followup_question,
    is_reorder_intent,
    missing_required,
)


class TestExtractCategory:
    @pytest.mark.parametrize(
        "text,expected",
        [
            ("想买蓝牙耳机", "蓝牙耳机"),
            ("想要个降噪耳机", "蓝牙耳机"),
            ("推荐个口红", "口红"),
            ("香薰礼盒有吗", "香薰"),
            ("想买个保温杯", "保温杯"),
            ("露营装备怎么选", "露营装备"),
            ("给女朋友买条项链", "项链"),
        ],
    )
    def test_specific_category(self, text: str, expected: str) -> None:
        assert extract_category(text) == expected

    @pytest.mark.parametrize(
        "text,expected",
        [
            ("想给女朋友买个生日礼物", "礼物"),
            ("有什么数码产品", "数码"),
            ("家用的小东西", "家居"),
        ],
    )
    def test_generic_category(self, text: str, expected: str) -> None:
        """泛品类词：用户说得比品类名抽象，靠场景映射命中"""
        assert extract_category(text) == expected

    @pytest.mark.parametrize("text", ["", "你好", "今天天气不错"])
    def test_no_category(self, text: str) -> None:
        assert extract_category(text) is None


class TestExtractBudget:
    @pytest.mark.parametrize(
        "text,expected",
        [
            ("300以内", "300以内"),
            ("预算300", "300以内"),
            ("不超过500", "500以内"),
            ("100-300", "100-300"),
            ("100到300", "100-300"),
            ("100~300", "100-300"),
            ("500以上", "500以上"),
            ("300元", "300以内"),
        ],
    )
    def test_budget_patterns(self, text: str, expected: str) -> None:
        assert extract_budget(text) == expected

    def test_budget_with_context(self) -> None:
        """预算词混在长句里也要能抽出来"""
        assert extract_budget("想给女朋友买个生日礼物，300以内") == "300以内"
        assert extract_budget("我预算大概 500 左右吧") == "500以内"

    @pytest.mark.parametrize("text", ["", "随便看看", "有什么推荐"])
    def test_no_budget(self, text: str) -> None:
        assert extract_budget(text) is None


class TestExtractScene:
    @pytest.mark.parametrize(
        "text,expected",
        [
            ("生日礼物", "生日"),
            ("送女朋友", "送礼"),
            ("去露营", "露营"),
            ("上班通勤用", "通勤"),
            ("跑步的时候", "运动"),
        ],
    )
    def test_scene(self, text: str, expected: str) -> None:
        assert extract_scene(text) == expected

    def test_no_scene(self) -> None:
        assert extract_scene("随便看看") is None


class TestFillSlots:
    def test_merge_does_not_overwrite_with_none(self) -> None:
        """用户这轮只说了预算，上轮的品类不能被清空。

        这是最容易写错的地方：如果用覆盖而不是合并，
        多轮对话里用户补充一个槽位，前面的就全丢了。
        """
        existing = {"category": "口红", "budget": None, "scene": None}
        result = fill_slots("300以内", existing)
        assert result["category"] == "口红"
        assert result["budget"] == "300以内"

    def test_merge_updates_category(self) -> None:
        existing = {"category": "口红", "budget": "300以内", "scene": None}
        result = fill_slots("还是看看耳机吧", existing)
        assert result["category"] == "蓝牙耳机"
        assert result["budget"] == "300以内", "预算不该被清空"

    def test_empty_input_keeps_existing(self) -> None:
        existing = {"category": "口红", "budget": "300以内", "scene": "生日"}
        result = fill_slots("嗯嗯好的", existing)
        assert result == existing

    def test_does_not_mutate_input(self) -> None:
        """必须返回新 dict，改返回值不能影响调用方的原对象"""
        existing = {"category": "口红", "budget": None, "scene": None}
        fill_slots("300以内", existing)
        assert existing["budget"] is None


class TestMissingRequired:
    def test_both_missing(self) -> None:
        assert missing_required({"category": None, "budget": None}) == ["category", "budget"]

    def test_category_missing(self) -> None:
        assert missing_required({"category": None, "budget": "300以内"}) == ["category"]

    def test_budget_missing(self) -> None:
        assert missing_required({"category": "口红", "budget": None}) == ["budget"]

    def test_all_present(self) -> None:
        assert missing_required({"category": "口红", "budget": "300以内"}) == []

    def test_none_input(self) -> None:
        assert missing_required(None) == ["category", "budget"]

    def test_empty_string_counts_as_missing(self) -> None:
        """空串等同于未收集，否则会拿空品类去检索"""
        assert missing_required({"category": "", "budget": "300以内"}) == ["category"]


class TestIntentGuards:
    @pytest.mark.parametrize(
        "text",
        ["再来一单上次买的洗衣液", "上次买的那个再买一次", "和上次一样", "补货"],
    )
    def test_reorder_intent(self, text: str) -> None:
        """「再来一单」不该被槽位追问拦住 —— 用户不想重挑"""
        assert is_reorder_intent(text) is True

    @pytest.mark.parametrize("text", ["想买口红", "500以内耳机", "推荐个礼物"])
    def test_not_reorder(self, text: str) -> None:
        assert is_reorder_intent(text) is False

    @pytest.mark.parametrize(
        "text",
        ["我刚才说预算多少来着", "这个怎么样", "这两款哪个好", "还有别的吗", "多少钱"],
    )
    def test_followup_question(self, text: str) -> None:
        """回指类问题指的是上文需求，不能拿去追问品类"""
        assert is_followup_question(text) is True

    @pytest.mark.parametrize("text", ["想买口红", "推荐个生日礼物"])
    def test_not_followup(self, text: str) -> None:
        assert is_followup_question(text) is False


class TestBuildSlotAsk:
    def test_category_ask(self) -> None:
        ask = build_slot_ask("category")
        assert ask["slot"] == "category"
        assert ask["question"]
        assert len(ask["options"]) > 0

    def test_budget_ask(self) -> None:
        ask = build_slot_ask("budget")
        assert ask["slot"] == "budget"
        assert "100以内" in ask["options"]

    def test_unknown_slot_does_not_crash(self) -> None:
        """未知槽位要降级而不是 KeyError —— 否则整条流会 500"""
        ask = build_slot_ask("unknown_field")
        assert ask["slot"] == "unknown_field"
        assert ask["options"] == []
