"""端到端自检。

一次跑完所有技术点的验证，输出可读报告。
不需要 Java 后端——商品接口未启动时 Agent 应如实告知（这本身就是一条验证项）。

用法：
    .venv/Scripts/python.exe scripts/e2e_check.py
    .venv/Scripts/python.exe scripts/e2e_check.py --with-mcp    # 额外验证联网工具（较慢）
"""

from __future__ import annotations

import argparse
import asyncio
import base64
import struct
import sys
import time
import zlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.agent import AgentState, _maybe_summarize, run_agent
from app.mcp_tools import mcp_toolkit
from app.memory import memory_store
from app.rag import knowledge_base
from app.schemas import AgentChatRequest, Slots

PASS = "[PASS]"
FAIL = "[FAIL]"
results: list[tuple[str, bool, str]] = []


def record(name: str, ok: bool, detail: str = "") -> None:
    results.append((name, ok, detail))
    print(f"  {PASS if ok else FAIL} {name}" + (f" -- {detail}" if detail else ""))


async def collect(req: AgentChatRequest) -> dict:
    """跑一轮对话，收集所有事件。"""
    events: list[tuple[str, dict]] = []
    text = ""
    async for evt in run_agent(req):
        events.append((evt.type, evt.data))
        if evt.type == "token":
            text += evt.data.get("text", "")
    return {
        "events": events,
        "types": [t for t, _ in events],
        "text": text,
        "products": next((d.get("products", []) for t, d in events if t == "product_card"), []),
        "errors": [d for t, d in events if t == "error"],
    }


def make_req(msg: str, session: str, *, slots: Slots | None = None, history=None, image=None):
    return AgentChatRequest(
        userId=10086,
        sessionId=session,
        message=msg,
        history=history or [],
        slots=slots or Slots(),
        image=image,
    )


def _solid_png(w: int, h: int, rgb: tuple[int, int, int]) -> str:
    raw = b"".join(b"\x00" + bytes(rgb) * w for _ in range(h))

    def chunk(t: bytes, d: bytes) -> bytes:
        c = t + d
        return struct.pack(">I", len(d)) + c + struct.pack(">I", zlib.crc32(c) & 0xFFFFFFFF)

    png = (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(raw))
        + chunk(b"IEND", b"")
    )
    return "data:image/png;base64," + base64.b64encode(png).decode()


async def main(with_mcp: bool) -> None:
    print("\n=== 0. 组件就绪检查 ===")
    t0 = time.monotonic()
    await knowledge_base.aload()
    record(
        "RAG 索引加载",
        knowledge_base.size > 0,
        f"{knowledge_base.size} 块, 降级={knowledge_base.degraded}, {(time.monotonic()-t0)*1000:.0f}ms",
    )

    mem = await memory_store.health()
    record("会话记忆后端", mem.get("ok", False), str(mem.get("backend")))

    await mcp_toolkit.ainit()
    record(
        "MCP 工具就绪",
        mcp_toolkit.ready,
        str([t.name for t in mcp_toolkit.tools]) if mcp_toolkit.ready else str(mcp_toolkit.error),
    )

    print("\n=== 1. 系统提示词 + RAG 注入 ===")
    await memory_store.reset("e2e_sys")
    r = await collect(make_req("300元送女朋友生日礼物，推荐一下", "e2e_sys"))
    record("产生 token 流", len(r["text"]) > 20, f"{len(r['text'])} 字")
    hit = any(k in r["text"] for k in ["不用解释", "包装", "礼盒", "香薰", "刻字", "品牌"])
    record("引用了站内导购知识", hit, "命中送礼场景知识" if hit else "未见知识特征")
    record("无 error 事件", not r["errors"], str(r["errors"])[:100] if r["errors"] else "")

    print("\n=== 2. 商品检索工具 ===")
    await memory_store.reset("e2e_tool")
    r2 = await collect(
        make_req(
            "搜300以内的降噪蓝牙耳机",
            "e2e_tool",
            slots=Slots(category="数码", budget="300以内"),
        )
    )
    record("触发 search_products", "tool_call" in r2["types"], str(sorted(set(r2["types"]))))
    if r2["products"]:
        p = r2["products"][0]
        record("返回商品卡片", True, f"{len(r2['products'])} 件, 首件「{p['title']}」¥{p['price']}")
        record("reason 字段非空", bool(p.get("reason")), str(p.get("reason", ""))[:50])
    else:
        # Java 未启动时没有商品是预期的，此时关键验证项变成"不编造"
        record(
            "商品接口不可用（跳过卡片校验）",
            True,
            "Java 后端未启动，属预期；改验证 Agent 是否如实告知",
        )
        record(
            "未编造商品",
            any(k in r2["text"] for k in ["无法", "暂时", "抱歉", "稍后", "取不到"]),
            r2["text"][:80].replace("\n", " "),
        )

    print("\n=== 3. 多轮会话记忆 ===")
    sid = "e2e_mem"
    await memory_store.reset(sid)
    await collect(make_req("我叫小林，想买个露营帐篷，预算500左右", sid))
    await asyncio.sleep(0.2)
    r3b = await collect(make_req("我刚才说我预算多少来着？", sid))
    turns = await memory_store.load_turns(sid)
    record("记忆写入", len(turns) >= 2, f"{len(turns)} 条")
    recall = any(k in r3b["text"] for k in ["500", "五百"])
    record("跨轮回忆预算", recall, r3b["text"][:80].replace("\n", " "))

    print("\n=== 4. 上下文工程（中间件裁剪） ===")
    from langchain_core.messages import AIMessage, HumanMessage, SystemMessage, ToolMessage

    from app.middleware import trim_messages

    msgs = [SystemMessage(content="sys")] + [
        HumanMessage(content=f"消息{i}" * 50) for i in range(30)
    ]
    trimmed = trim_messages(msgs, max_messages=10, max_chars=4000)
    record("按条数裁剪生效", len(trimmed) <= 10, f"30 -> {len(trimmed)} 条")
    record("保留 system 提示词", trimmed[0].type == "system")

    pair = [
        SystemMessage(content="sys"),
        AIMessage(content="", tool_calls=[{"name": "t", "args": {}, "id": "c1"}]),
        ToolMessage(content="r", tool_call_id="c1"),
        HumanMessage(content="后续" * 2000),
    ]
    t2 = trim_messages(pair, max_messages=3, max_chars=100)
    orphan = any(isinstance(m, ToolMessage) for m in t2) and not any(
        isinstance(m, AIMessage) and getattr(m, "tool_calls", None) for m in t2
    )
    record("不产生孤儿 tool_result", not orphan, f"裁剪后 {[m.type for m in t2]}")

    print("\n=== 5. State 动态数据 ===")
    record("State 含业务字段", "slots" in AgentState.__annotations__, str(list(AgentState.__annotations__)))

    print("\n=== 6. 多模态（识图） ===")
    await memory_store.reset("e2e_vl")
    r6 = await collect(make_req("这个颜色适合买什么", "e2e_vl", image=_solid_png(96, 96, (40, 90, 200))))
    vision_evt = [d for t, d in r6["events"] if t == "tool_result" and d.get("tool") == "vision"]
    record("识图事件产出", bool(vision_evt), str(vision_evt[0].get("preview", ""))[:60] if vision_evt else "")
    record(
        "识图内容进入回答",
        any(k in r6["text"] for k in ["蓝"]),
        r6["text"][:80].replace("\n", " "),
    )

    if with_mcp:
        print("\n=== 7. MCP 联网工具 ===")
        await memory_store.reset("e2e_mcp")
        r7 = await collect(make_req("帮我查一下 2026 年露营帐篷哪个牌子口碑好", "e2e_mcp"))
        tool_names = [d.get("tool") for t, d in r7["events"] if t == "tool_call"]
        record("调用联网工具", "tavily_search" in tool_names, str(tool_names))
        record("联网结果进入回答", len(r7["text"]) > 50, r7["text"][:80].replace("\n", " "))

    print("\n=== 8. 记忆摘要压缩 ===")
    sid2 = "e2e_sum"
    await memory_store.reset(sid2)
    for i in range(8):
        await memory_store.append_turn(sid2, "user", f"第{i}轮：我想买露营装备，预算{i * 100}元")
        await memory_store.append_turn(sid2, "assistant", f"好的，第{i}轮我推荐帐篷{i}")
    before = len(await memory_store.load_turns(sid2))
    await _maybe_summarize(sid2)
    after = len(await memory_store.load_turns(sid2))
    summary = await memory_store.get_summary(sid2)
    record("触发摘要压缩", before > after, f"{before} -> {after} 条")
    record("摘要已生成", bool(summary), summary[:60])

    ok = sum(1 for _, o, _ in results if o)
    total = len(results)
    print(f"\n{'=' * 52}")
    print(f"结果：{ok}/{total} 通过")
    if ok < total:
        print("失败项：")
        for n, o, d in results:
            if not o:
                print(f"  - {n}: {d}")
    print("=" * 52)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--with-mcp", action="store_true", help="额外验证联网检索（较慢）")
    args = ap.parse_args()
    asyncio.run(main(args.with_mcp))
