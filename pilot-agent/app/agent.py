"""Agent 编排层。

一次请求的完整流程：

    请求进来
      ├─ 1. 多模态预处理（有图先识图，把视觉信息转成文本）
      ├─ 2. 取用户画像（Java 提供）
      ├─ 3. RAG 检索导购知识（按用户问题 + 图片描述召回）
      ├─ 4. 拼系统提示词（静态规则 + 画像 + 摘要 + 知识 + 槽位）
      ├─ 5. 载入会话记忆，与 Java history 合并
      ├─ 6. LangGraph ReAct 循环（模型 ↔ 工具，穿过三个中间件）
      └─ 7. 流式产出 SSE + 回写记忆（超阈值触发摘要压缩）

为什么用 create_agent 而不是手写 StateGraph：
ReAct 循环（模型判断要不要调工具 → 调完喂回模型 → 再判断）是标准模式，
create_agent 已处理好并行工具调用、tool_call 与 tool_result 配对等边界情况。
我们的业务逻辑通过 middleware + state_schema 注入，不必重造轮子。

State 里的动态数据：
state 不只有 messages，还带本轮算出来的 slots / knowledge / image_desc。
这些是"上下文工程"的落点——模型看到什么，由 state 决定，而不是由调用方随手拼。
"""

from __future__ import annotations

import logging
import time
import uuid
from collections.abc import AsyncIterator
from dataclasses import dataclass, field
from typing import Annotated, Any, TypedDict

from langchain.agents import create_agent
from langchain_core.messages import (
    AIMessage,
    BaseMessage,
    HumanMessage,
    SystemMessage,
)
from langgraph.graph.message import add_messages

from app.config import get_settings
from app.llm import get_chat_model, get_vl_model
from app.mcp_tools import mcp_toolkit
from app.memory import memory_store
from app.middleware import AuditMiddleware, ContextTrimMiddleware, ToolRetryMiddleware
from app.prompts import build_system_prompt
from app.rag import knowledge_base
from app.runtime import ToolArtifact, current_artifact, current_trace_id, current_user_id
from app.schemas import AgentChatRequest, ProductCard, SseEvent
from app.tools import BUSINESS_TOOLS

logger = logging.getLogger("pilot.agent")


# --------------------------------------------------------------------------
# State：除消息外，携带本轮动态数据
# --------------------------------------------------------------------------

class AgentState(TypedDict, total=False):
    messages: Annotated[list[BaseMessage], add_messages]
    # 以下为动态数据，跨轮可复用
    slots: dict[str, str | None]
    knowledge: str
    image_desc: str


@dataclass
class PreparedContext:
    """请求预处理结果。集中一处，避免参数在函数间乱飞。"""

    system_prompt: str
    history: list[BaseMessage] = field(default_factory=list)
    user_text: str = ""
    image_desc: str = ""


# --------------------------------------------------------------------------
# 多模态：图片 → 文本
# --------------------------------------------------------------------------

async def describe_image(data_url: str, user_text: str) -> str:
    """调用 VL 模型识图，返回文本描述。

    为什么先转文本再进主流程：
    主模型 qwen-plus 不吃图片，而且把图片塞进 ReAct 循环会导致
    每轮工具调用都重复传图（贵且慢）。先转成一次性描述，
    后续所有轮次复用同一段文本，成本降一个数量级。
    """
    if not data_url:
        return ""
    try:
        msg = HumanMessage(
            content=[
                {
                    "type": "text",
                    "text": (
                        "用户发来一张图片，可能在咨询图中商品。请描述：\n"
                        "1) 图中是什么商品（品类、外观、颜色、材质）；\n"
                        "2) 能看到的品牌、型号、文字信息；\n"
                        "3) 若要找同款或替代品，关键特征是什么。\n"
                        f"用户提问：{user_text or '（未附文字）'}\n"
                        "只输出描述，不寒暄，不超过 150 字。"
                    ),
                },
                {"type": "image_url", "image_url": {"url": data_url}},
            ]
        )
        resp = await get_vl_model().ainvoke([msg])
        text = resp.text or str(resp.content)
        logger.info("vision: 识图完成 %d 字", len(text))
        return text.strip()
    except Exception as exc:  # noqa: BLE001 - 识图失败不能阻断对话
        logger.warning("vision: 识图失败 %s", exc)
        return ""


def _normalize_image(url: str) -> str:
    """裸 base64 补成 data URL。前端可能只传 base64。"""
    if not url:
        return ""
    if url.startswith(("http://", "https://", "data:")):
        return url
    return f"data:image/jpeg;base64,{url}"


# --------------------------------------------------------------------------
# 记忆 → 消息
# --------------------------------------------------------------------------

async def _build_history(req: AgentChatRequest) -> list[BaseMessage]:
    """合并 Java history 与 Redis 记忆，去重后转消息。

    为什么两边都要：Java 的 history 是权威业务记录（用户能看到的历史），
    Redis 是运行态（含 Java 尚未落库的最近一轮）。
    以 Java 为准，Redis 补缺口，靠 (role, content) 去重。
    """
    seen: set[tuple[str, str]] = set()
    messages: list[BaseMessage] = []

    def push(role: str, content: str) -> None:
        key = (role, content.strip())
        if not content.strip() or key in seen:
            return
        seen.add(key)
        messages.append(
            HumanMessage(content=content) if role == "user" else AIMessage(content=content)
        )

    for h in req.history:
        push(h.role, h.content)

    for turn in await memory_store.load_turns(req.session_id):
        push(turn.role, turn.content)

    return messages


# --------------------------------------------------------------------------
# 预处理：拼上下文
# --------------------------------------------------------------------------

async def prepare(req: AgentChatRequest) -> PreparedContext:
    """把一次请求加工成模型能吃的完整上下文。"""
    s = get_settings()

    # 1) 多模态
    image_desc = ""
    if req.image:
        image_desc = await describe_image(_normalize_image(req.image), req.message)

    # 2) 用户画像（Java 提供，失败不阻断）
    user_profile = ""
    try:
        from app.java_client import java_client

        profile = await java_client.get_user_profile(req.user_id)
        if profile:
            bits = []
            if profile.get("preferredCategories"):
                bits.append(f"偏好品类：{'、'.join(profile['preferredCategories'][:5])}")
            if profile.get("avgOrderAmount"):
                bits.append(f"历史客单价：约 {profile['avgOrderAmount']} 元")
            if profile.get("memberLevel"):
                bits.append(f"会员等级：{profile['memberLevel']}")
            user_profile = "；".join(bits)
    except Exception as exc:  # noqa: BLE001
        logger.info("profile: 取用户画像失败(可忽略) %s", exc)

    # 3) RAG：用"用户问题 + 图片描述"检索，图片信息能提升召回
    query = f"{req.message} {image_desc}".strip()
    knowledge = ""
    if query:
        try:
            knowledge = await knowledge_base.aformat(query, top_k=3)
        except Exception as exc:  # noqa: BLE001
            logger.warning("rag: 检索失败 %s", exc)

    # 4) 记忆摘要
    summary = await memory_store.get_summary(req.session_id)

    # 5) 系统提示词
    system_prompt = build_system_prompt(
        user_profile=user_profile or None,
        memory_summary=summary or None,
        knowledge=knowledge or None,
        slots=req.slots.model_dump(),
        multimodal_hint=image_desc or None,
    )

    # 6) 历史（先按条数截断，中间件还会按 token 预算再裁一次）
    history = await _build_history(req)
    if len(history) > s.context_max_history:
        history = history[-s.context_max_history :]

    return PreparedContext(
        system_prompt=system_prompt,
        history=history,
        user_text=req.message,
        image_desc=image_desc,
    )


# --------------------------------------------------------------------------
# 图构建
# --------------------------------------------------------------------------

async def build_graph():
    """组装 Agent。工具集按 MCP 可用性动态决定。"""
    await mcp_toolkit.ainit()
    tools = [*BUSINESS_TOOLS]
    if mcp_toolkit.ready:
        tools.extend(mcp_toolkit.tools)

    return create_agent(
        model=get_chat_model(),
        tools=tools,
        system_prompt=None,  # 每轮动态拼，由 inputs 里的 SystemMessage 提供
        middleware=[
            ContextTrimMiddleware(),
            ToolRetryMiddleware(retries=1),
            AuditMiddleware(),
        ],
        state_schema=AgentState,
        name="shoppilot",
    )


# --------------------------------------------------------------------------
# 主流程：产出 SSE
# --------------------------------------------------------------------------

def _to_card(p: dict, reason: str = "") -> dict:
    """商品原始数据 → 卡片。字段名按契约转 camelCase。

    reason 优先用 Java 给的场景化理由；Java 没给时用副标题兜底。
    契约里 reason 是必填字段，空字符串会让前端渲染出空白推荐语。
    """
    final_reason = reason or p.get("reason") or p.get("subTitle") or p.get("sub_title") or ""
    return ProductCard(
        product_id=int(p.get("productId") or p.get("product_id") or 0),
        title=p.get("title") or "",
        sub_title=p.get("subTitle") or p.get("sub_title"),
        price=float(p.get("price") or 0),
        original_price=float(p["originalPrice"]) if p.get("originalPrice") else None,
        cover_url=p.get("coverUrl") or p.get("cover_url"),
        stock=int(p.get("stock") or 0),
        sales_count=int(p.get("salesCount") or p.get("sales_count") or 0),
        rating=p.get("rating"),
        tags=p.get("tags") or [],
        reason=final_reason,
    ).model_dump(by_alias=True)


async def run_agent(req: AgentChatRequest) -> AsyncIterator[SseEvent]:
    """产出 SSE 事件流。这是 Agent 的唯一出口。"""
    started = time.monotonic()
    settings = get_settings()
    message_id = f"m_{uuid.uuid4().hex[:10]}"
    trace_id = req.trace_id or uuid.uuid4().hex

    current_user_id.set(req.user_id)
    current_trace_id.set(trace_id)
    artifact = ToolArtifact()
    current_artifact.set(artifact)

    yield SseEvent(
        type="meta",
        data={
            "sessionId": req.session_id,
            "messageId": message_id,
            "model": settings.llm_model,
            "traceId": trace_id,
        },
    )

    try:
        prepared = await prepare(req)
    except Exception as exc:
        logger.exception("prepare 失败")
        yield SseEvent(
            type="error",
            data={"code": "AGENT_ERROR", "message": f"上下文准备失败：{exc}", "retryable": True},
        )
        return

    # 识图结果先告知前端，让用户看到"我理解了什么"
    if prepared.image_desc:
        yield SseEvent(
            type="tool_result",
            data={
                "tool": "vision",
                "count": 1,
                "elapsedMs": 0,
                "preview": prepared.image_desc[:120],
            },
        )

    try:
        graph = await build_graph()
    except Exception as exc:
        logger.exception("graph 构建失败")
        yield SseEvent(
            type="error",
            data={"code": "AGENT_ERROR", "message": f"Agent 初始化失败：{exc}", "retryable": True},
        )
        return

    inputs: dict[str, Any] = {
        "messages": [
            SystemMessage(content=prepared.system_prompt),
            *prepared.history,
            HumanMessage(content=prepared.user_text or "（用户发来一张图片）"),
        ],
        "slots": req.slots.model_dump(),
        "knowledge": prepared.system_prompt,
        "image_desc": prepared.image_desc,
    }

    full_text: list[str] = []
    tool_count = 0
    emitted_products = False

    try:
        async for event in graph.astream_events(inputs, version="v2"):
            kind = event.get("event")

            # ---- 模型流式输出 ----
            if kind == "on_chat_model_stream":
                chunk = event.get("data", {}).get("chunk")
                if chunk is None:
                    continue
                text = chunk.text or ""
                if text:
                    full_text.append(text)
                    yield SseEvent(type="token", data={"text": text})

            # ---- 工具开始 ----
            elif kind == "on_tool_start":
                name = event.get("name", "")
                display = {
                    "search_products": "正在为您筛选商品…",
                    "get_product_detail": "正在查看商品详情…",
                    "get_order_history": "正在查询您的历史订单…",
                    "tavily_search": "正在联网查证…",
                    "tavily_extract": "正在读取网页内容…",
                }.get(name, f"正在执行 {name}…")
                tool_count += 1
                yield SseEvent(
                    type="tool_call",
                    data={
                        "tool": name,
                        "displayText": display,
                        "args": event.get("data", {}).get("input", {}),
                    },
                )

            # ---- 工具结束 ----
            elif kind == "on_tool_end":
                name = event.get("name", "")
                output = event.get("data", {}).get("output")
                content = getattr(output, "content", output)
                yield SseEvent(
                    type="tool_result",
                    data={
                        "tool": name,
                        "count": 1,
                        "elapsedMs": 0,
                        "preview": str(content)[:120],
                    },
                )
                # 商品卡片：工具一返回就推，不等模型说完，用户体感更快
                if artifact.products and not emitted_products:
                    emitted_products = True
                    yield SseEvent(
                        type="product_card",
                        data={"products": [_to_card(p) for p in artifact.products]},
                    )

    except Exception as exc:
        logger.exception("agent 执行失败")
        yield SseEvent(
            type="error",
            data={
                "code": "AGENT_ERROR",
                "message": f"生成回答时出错：{exc}",
                "retryable": True,
            },
        )
        return

    if artifact.products and not emitted_products:
        yield SseEvent(
            type="product_card",
            data={"products": [_to_card(p) for p in artifact.products]},
        )

    answer = "".join(full_text).strip()

    # 回写记忆：只存纯文本。工具轨迹不入记忆——会污染后续上下文且极耗 token
    try:
        await memory_store.append_turn(req.session_id, "user", req.message)
        if answer:
            await memory_store.append_turn(req.session_id, "assistant", answer)
        await _maybe_summarize(req.session_id)
    except Exception as exc:  # noqa: BLE001
        logger.warning("memory: 回写失败 %s", exc)

    yield SseEvent(
        type="done",
        data={
            "messageId": message_id,
            "finishReason": "stop",
            "totalTokens": len(answer),
            "toolCalls": tool_count,
            "latencyMs": int((time.monotonic() - started) * 1000),
        },
    )


async def _maybe_summarize(session_id: str) -> None:
    """超过阈值时把早期对话压成摘要。

    用便宜的一次模型调用，比每轮都带全量历史划算得多。
    失败不影响主流程——最坏是上下文继续变长，中间件会兜住。
    """
    s = get_settings()
    turns = await memory_store.load_turns(session_id)
    if len(turns) <= s.memory_max_turns:
        return

    head = turns[: len(turns) - s.memory_keep_turns]
    if not head:
        return

    transcript = "\n".join(f"{t.role}: {t.content[:300]}" for t in head)
    old_summary = await memory_store.get_summary(session_id)

    try:
        resp = await get_chat_model().ainvoke(
            [
                SystemMessage(
                    content=(
                        "你是对话摘要器。把下面的导购对话压成不超过 120 字的摘要，"
                        "只保留：用户的核心需求、预算、已排除的选项、已确认的偏好。"
                        "不要复述寒暄，不要编造信息。直接输出摘要正文。"
                    )
                ),
                HumanMessage(
                    content=f"已有摘要：{old_summary or '（无）'}\n\n新增对话：\n{transcript}"
                ),
            ]
        )
        summary = resp.text or str(resp.content)
        await memory_store.set_summary(session_id, summary.strip()[:600])
        await memory_store.trim(session_id)
        logger.info("memory: 摘要已更新，压缩 %d 条", len(turns) - s.memory_keep_turns)
    except Exception as exc:  # noqa: BLE001
        logger.warning("memory: 摘要失败 %s", exc)
