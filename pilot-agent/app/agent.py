"""Agent 编排层。

当前实现为 mock 流式：不依赖 LLM 也能跑通三端链路，
方便先验证 SSE 透传，再接真实模型。

接真实模型时，把 `run_agent` 内部替换为 LangGraph 的 astream_events 即可，
事件产出结构保持不变（SseEvent）。
"""

import asyncio
import time
from collections.abc import AsyncIterator

from app.config import get_settings
from app.java_client import java_client
from app.schemas import AgentChatRequest, ProductCard, SseEvent

# 必填槽位：缺任何一个都要先追问，不执行检索
REQUIRED_SLOTS = ("category", "budget")


def _missing_slots(req: AgentChatRequest) -> list[str]:
    return [s for s in REQUIRED_SLOTS if getattr(req.slots, s) in (None, "")]


def _mock_reply(req: AgentChatRequest) -> str:
    return (
        f"根据您的需求「{req.message}」，我从商品库里筛选出几款符合预算的选择。"
        "这几款都在 300 元以内，其中第一款带礼盒包装，送人比较合适；"
        "第二款可以定制刻字，更有纪念意义。您更看重包装还是个性化？"
    )


async def run_agent(req: AgentChatRequest) -> AsyncIterator[SseEvent]:
    """产出 SSE 事件流。这是 Agent 的唯一出口。"""
    started = time.monotonic()
    settings = get_settings()

    yield SseEvent(
        type="meta",
        data={
            "sessionId": req.session_id,
            "messageId": f"m_{int(started * 1000)}",
            "model": settings.llm_model,
        },
    )

    # 1) 槽位检查：缺信息先追问，不检索
    missing = _missing_slots(req)
    if missing:
        slot = missing[0]
        questions = {
            "category": ("您想买哪类商品呢？比如礼物、数码、家居", ["礼物", "数码", "家居", "服饰"]),
            "budget": ("大概预算多少呢？我好帮您筛选", ["100以内", "100-300", "300-500", "500以上"]),
        }
        question, options = questions[slot]
        yield SseEvent(
            type="slot_ask",
            data={"slot": slot, "question": question, "options": options},
        )
        yield SseEvent(
            type="done",
            data={
                "messageId": f"m_{int(started * 1000)}",
                "finishReason": "slot_ask",
                "latencyMs": int((time.monotonic() - started) * 1000),
            },
        )
        return

    # 2) 工具调用：检索商品
    yield SseEvent(
        type="tool_call",
        data={
            "tool": "search_products",
            "displayText": "正在为您筛选商品…",
            "args": {"category": req.slots.category, "maxPrice": req.slots.budget},
        },
    )

    tool_started = time.monotonic()
    try:
        products = await java_client.search_products(
            query=req.message, category=req.slots.category, limit=5
        )
    except Exception as exc:  # noqa: BLE001 - 工具失败不能拖死整条流
        yield SseEvent(
            type="error",
            data={"code": "UPSTREAM_ERROR", "message": f"商品检索失败：{exc}", "retryable": True},
        )
        return

    yield SseEvent(
        type="tool_result",
        data={
            "tool": "search_products",
            "count": len(products),
            "elapsedMs": int((time.monotonic() - tool_started) * 1000),
        },
    )

    # 3) 商品卡片
    cards = []
    for p in products:
        cards.append(
            ProductCard(
                product_id=p["productId"],
                title=p["title"],
                sub_title=p.get("subTitle"),
                price=float(p["price"]),
                original_price=float(p["originalPrice"]) if p.get("originalPrice") else None,
                stock=p.get("stock", 0),
                sales_count=p.get("salesCount", 0),
                rating=p.get("rating"),
                tags=p.get("tags", []),
                reason="预算内且带礼盒包装，适合送人",
            ).model_dump(by_alias=True)
        )
    yield SseEvent(type="product_card", data={"products": cards})

    # 4) 逐字输出。真实实现改为 LLM astream 的 token 迭代
    reply = _mock_reply(req)
    for ch in reply:
        yield SseEvent(type="token", data={"text": ch})
        await asyncio.sleep(0.012)

    yield SseEvent(
        type="done",
        data={
            "messageId": f"m_{int(started * 1000)}",
            "finishReason": "stop",
            "totalTokens": len(reply),
            "latencyMs": int((time.monotonic() - started) * 1000),
        },
    )
