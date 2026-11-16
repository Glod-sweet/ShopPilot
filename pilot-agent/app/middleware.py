"""中间件。

中间件是 Agent 的横切关注点，不该混进业务工具里。
这里实现三个：

1. ContextTrimMiddleware —— 上下文工程。按条数和字符数双重预算裁剪历史，
   并保证裁剪不切断 tool_call / tool_result 的配对（切断会让 OpenAI 协议直接报错）。
2. AuditMiddleware —— 可观测性。记录每轮的工具调用、耗时、token 消耗，
   落结构化日志，方便事后排查"为什么这轮答得不对"。
3. ToolRetryMiddleware —— 容错。工具失败重试一次，仍失败则把错误喂回模型，
   让模型自己决定降级方案，而不是整条流直接 500。

为什么用中间件而不是在工具里 try/except：
工具内部捕获异常会丢失堆栈上下文，且每个工具都要写一遍。
中间件在协议层统一处理，工具保持纯粹。
"""

from __future__ import annotations

import asyncio
import logging
import time
from collections.abc import Awaitable, Callable
from typing import Any

from langchain.agents.middleware import AgentMiddleware
from langchain_core.messages import AIMessage, BaseMessage, ToolMessage

from app.config import get_settings

logger = logging.getLogger("pilot.agent")


# --------------------------------------------------------------------------
# 1. 上下文工程
# --------------------------------------------------------------------------

def _is_tool_pair_start(msg: BaseMessage) -> bool:
    return isinstance(msg, AIMessage) and bool(getattr(msg, "tool_calls", None))


def trim_messages(
    messages: list[BaseMessage], *, max_messages: int, max_chars: int
) -> list[BaseMessage]:
    """按条数 + 字符数双预算裁剪历史。

    两条不变量：
    - 首条若是 system 消息必须保留（提示词丢了，Agent 就失忆了）。
    - AI 的 tool_calls 与其后的 ToolMessage 必须同生同死。
      只删一半会让下一次请求被上游拒绝（400: tool_call_id not found）。

    策略是"从尾部向前累加"，超预算就停，这保证留下的一定是最近的对话。
    """
    if not messages:
        return messages

    head: list[BaseMessage] = []
    body = messages
    if isinstance(messages[0], type(messages[0])) and getattr(messages[0], "type", "") == "system":
        head, body = [messages[0]], messages[1:]

    budget_msgs = max_messages - len(head)
    budget_chars = max_chars

    kept: list[BaseMessage] = []
    for msg in reversed(body):
        text = msg.text or str(msg.content)
        cost = len(text) if isinstance(text, str) else 0
        if len(kept) + 1 > budget_msgs or budget_chars - cost < 0:
            break
        kept.append(msg)
        budget_chars -= cost

    kept.reverse()

    # 修正边界：若保留段的首条是 ToolMessage，说明它的 AIMessage 被裁掉了，
    # 继续向后丢弃，直到起点是一个干净的"对话开头"。
    while kept and isinstance(kept[0], ToolMessage):
        kept.pop(0)
    # 若保留段首条是带 tool_calls 的 AIMessage，但对应的 ToolMessage 已被裁掉，
    # 则去掉 tool_calls 只留文本，避免协议不完整。
    if kept and _is_tool_pair_start(kept[0]):
        first = kept[0]
        kept[0] = AIMessage(content=first.text or "")

    return head + kept


class ContextTrimMiddleware(AgentMiddleware):
    """在每次调模型前裁剪上下文。

    预算不是固定值，而是按 state 动态调整：
    - 槽位已收集齐全 → 需求明确，历史可以裁得更狠，把预算留给工具结果
    - 槽位缺失 → 需要更多历史来判断用户是否间接补全了信息，放宽预算

    这是 state 动态数据真正被消费的地方：state 不只是"存着"，而是影响决策。
    """

    name = "context_trim"

    async def abefore_model(self, state: Any, runtime: Any) -> dict | None:
        s = get_settings()
        messages = state.get("messages") or []

        slots = state.get("slots") or {}
        filled = sum(1 for v in slots.values() if v)
        # 槽位齐全时收紧预算（0.75 倍），缺失时放宽（1.25 倍），夹在合理区间
        factor = 0.75 if filled >= 2 else 1.25 if filled == 0 else 1.0
        max_msgs = max(6, int(s.context_max_history * factor))
        max_chars = max(2000, int(s.context_max_chars * factor))

        trimmed = trim_messages(
            list(messages),
            max_messages=max_msgs,
            max_chars=max_chars,
        )
        if len(trimmed) != len(messages):
            logger.info(
                "context_trim %d -> %d messages (slots_filled=%d, factor=%.2f)",
                len(messages),
                len(trimmed),
                filled,
                factor,
            )
            return {"messages": trimmed}
        return None


# --------------------------------------------------------------------------
# 2. 审计日志
# --------------------------------------------------------------------------

class AuditMiddleware(AgentMiddleware):
    """记录工具调用的耗时与结果规模，输出结构化日志。"""

    name = "audit"

    async def awrap_tool_call(
        self,
        request: Any,
        handler: Callable[[Any], Awaitable[Any]],
    ) -> Any:
        name = getattr(request, "tool_call", {}).get("name", "unknown")
        started = time.monotonic()
        try:
            result = await handler(request)
        except Exception as exc:
            logger.warning(
                "tool_fail tool=%s elapsed_ms=%d err=%s",
                name,
                (time.monotonic() - started) * 1000,
                exc,
            )
            raise
        logger.info(
            "tool_ok tool=%s elapsed_ms=%d result_chars=%d",
            name,
            (time.monotonic() - started) * 1000,
            len(str(getattr(result, "content", ""))),
        )
        return result


# --------------------------------------------------------------------------
# 3. 工具重试
# --------------------------------------------------------------------------

class ToolRetryMiddleware(AgentMiddleware):
    """工具失败重试一次。

    重试仍失败时不抛异常，而是构造 ToolMessage 把错误喂回模型。
    理由：MCP 网络检索、Java 内部接口都可能瞬时抖动，
    整条流 500 对用户体验的伤害，远大于让模型说一句"检索暂时不可用，我按已有信息回答"。
    """

    name = "tool_retry"

    def __init__(self, *, retries: int = 1, backoff: float = 0.4) -> None:
        super().__init__()
        self._retries = retries
        self._backoff = backoff

    async def awrap_tool_call(
        self,
        request: Any,
        handler: Callable[[Any], Awaitable[Any]],
    ) -> Any:
        last_exc: Exception | None = None
        for attempt in range(self._retries + 1):
            try:
                return await handler(request)
            except Exception as exc:  # noqa: BLE001 - 统一降级
                last_exc = exc
                if attempt < self._retries:
                    await asyncio.sleep(self._backoff * (attempt + 1))

        call = getattr(request, "tool_call", {}) or {}
        logger.error("tool_exhausted tool=%s err=%s", call.get("name"), last_exc)
        return ToolMessage(
            content=(
                f"工具执行失败：{last_exc}。"
                "请基于已知信息继续回答，并明确告知用户这部分数据暂时取不到，不要编造。"
            ),
            tool_call_id=call.get("id", ""),
            name=call.get("name", ""),
            status="error",
        )
