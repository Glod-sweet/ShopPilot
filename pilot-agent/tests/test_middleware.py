"""上下文裁剪测试。

这块是整个 Agent 里最容易出隐蔽 bug 的地方：
裁剪切断了 tool_call 与 tool_result 的配对，OpenAI 协议会直接返回 400，
而且报错信息是 "tool_call_id not found"，不看代码根本不知道是自己裁错了。

所以这里测的不是「能不能裁」，而是「裁剪后协议是否仍然完整」。
"""

from __future__ import annotations

from langchain_core.messages import AIMessage, HumanMessage, SystemMessage, ToolMessage

from app.middleware import trim_messages


def _human(text: str) -> HumanMessage:
    return HumanMessage(content=text)


def _ai_tool_call(call_id: str) -> AIMessage:
    return AIMessage(
        content="",
        tool_calls=[{"name": "search_products", "args": {"keyword": "耳机"}, "id": call_id}],
    )


def _tool_result(call_id: str) -> ToolMessage:
    return ToolMessage(content="检索到 3 件商品", tool_call_id=call_id, name="search_products")


class TestBasicTrim:
    def test_under_budget_returns_unchanged(self) -> None:
        """没超预算时不该改动任何东西"""
        msgs = [SystemMessage(content="sys"), _human("你好"), AIMessage(content="您好")]
        out = trim_messages(msgs, max_messages=10, max_chars=4000)
        assert out == msgs

    def test_empty_input(self) -> None:
        assert trim_messages([], max_messages=10, max_chars=4000) == []

    def test_trim_by_message_count(self) -> None:
        msgs = [SystemMessage(content="sys")] + [_human(f"消息{i}") for i in range(30)]
        out = trim_messages(msgs, max_messages=10, max_chars=100000)
        assert len(out) == 10

    def test_trim_by_char_budget(self) -> None:
        msgs = [SystemMessage(content="sys")] + [_human("啊" * 500) for _ in range(10)]
        out = trim_messages(msgs, max_messages=100, max_chars=1200)
        assert len(out) < len(msgs)

    def test_keeps_most_recent(self) -> None:
        """必须留下最近的对话，而不是最早的"""
        msgs = [SystemMessage(content="sys")] + [_human(f"消息{i}") for i in range(20)]
        out = trim_messages(msgs, max_messages=5, max_chars=100000)
        texts = [m.text for m in out if isinstance(m, HumanMessage)]
        assert "消息19" in texts
        assert "消息0" not in texts


class TestSystemPromptInvariant:
    def test_system_message_always_kept(self) -> None:
        """首条 system 消息丢了，Agent 就失忆了 —— 这是不可接受的"""
        msgs = [SystemMessage(content="你是导购助手")] + [_human("啊" * 1000) for _ in range(20)]
        out = trim_messages(msgs, max_messages=3, max_chars=100)
        assert out[0].type == "system"
        assert out[0].content == "你是导购助手"

    def test_no_system_message_works(self) -> None:
        msgs = [_human(f"消息{i}") for i in range(20)]
        out = trim_messages(msgs, max_messages=5, max_chars=100000)
        assert len(out) <= 5


class TestToolPairInvariant:
    """tool_call 与 tool_result 必须同生同死。

    这是整个模块最重要的不变量：只留一半会让上游返回
    400: "tool_call_id not found"，整条流直接断掉。
    """

    def test_no_orphan_tool_message_at_head(self) -> None:
        """裁剪后首条不能是孤立的 ToolMessage（它的 AIMessage 被裁掉了）"""
        msgs = [
            SystemMessage(content="sys"),
            _human("想买耳机"),
            _ai_tool_call("c1"),
            _tool_result("c1"),
            _human("啊" * 500),
            _human("啊" * 500),
        ]
        out = trim_messages(msgs, max_messages=3, max_chars=600)

        # 找到第一个非 system 消息，它不能是 ToolMessage
        body = [m for m in out if m.type != "system"]
        if body:
            assert body[0].type != "tool", f"首条是孤立 ToolMessage: {[m.type for m in out]}"

    def test_no_dangling_tool_calls_at_head(self) -> None:
        """裁剪后首条若是带 tool_calls 的 AIMessage，其 ToolMessage 必须还在"""
        msgs = [
            SystemMessage(content="sys"),
            _ai_tool_call("c1"),
            _tool_result("c1"),
            _human("啊" * 800),
        ]
        out = trim_messages(msgs, max_messages=2, max_chars=400)

        has_tool_call = any(
            isinstance(m, AIMessage) and getattr(m, "tool_calls", None) for m in out
        )
        has_tool_result = any(isinstance(m, ToolMessage) for m in out)
        # 要么都有，要么都没有，不能只有一个
        assert has_tool_call == has_tool_result, (
            f"配对被切断: tool_call={has_tool_call} tool_result={has_tool_result}, "
            f"messages={[m.type for m in out]}"
        )

    def test_intact_pair_preserved_when_budget_allows(self) -> None:
        """预算够的时候，完整的工具调用轮次要保留下来"""
        msgs = [
            SystemMessage(content="sys"),
            _human("想买耳机"),
            _ai_tool_call("c1"),
            _tool_result("c1"),
        ]
        out = trim_messages(msgs, max_messages=10, max_chars=10000)
        assert any(isinstance(m, ToolMessage) for m in out)
        assert any(isinstance(m, AIMessage) and m.tool_calls for m in out)

    def test_multiple_pairs_no_partial(self) -> None:
        """多轮工具调用时，不能出现「前一个的 result 在、后一个的 call 没了」"""
        msgs = [
            SystemMessage(content="sys"),
            _ai_tool_call("c1"),
            _tool_result("c1"),
            _ai_tool_call("c2"),
            _tool_result("c2"),
            _human("啊" * 600),
        ]
        out = trim_messages(msgs, max_messages=4, max_chars=300)

        call_ids = {
            m.tool_calls[0]["id"]
            for m in out
            if isinstance(m, AIMessage) and getattr(m, "tool_calls", None)
        }
        result_ids = {m.tool_call_id for m in out if isinstance(m, ToolMessage)}
        assert result_ids <= call_ids, f"存在孤儿 tool_result: {result_ids - call_ids}"


class TestEdgeCases:
    def test_single_message(self) -> None:
        msgs = [SystemMessage(content="sys")]
        out = trim_messages(msgs, max_messages=10, max_chars=4000)
        assert out == msgs

    def test_zero_budget_still_keeps_system(self) -> None:
        """预算为 0 也不能把 system 丢了"""
        msgs = [SystemMessage(content="sys"), _human("你好")]
        out = trim_messages(msgs, max_messages=0, max_chars=0)
        assert out[0].type == "system"

    def test_does_not_mutate_input(self) -> None:
        msgs = [SystemMessage(content="sys")] + [_human(f"消息{i}") for i in range(20)]
        original_len = len(msgs)
        trim_messages(msgs, max_messages=5, max_chars=1000)
        assert len(msgs) == original_len, "不能修改传入的列表"
