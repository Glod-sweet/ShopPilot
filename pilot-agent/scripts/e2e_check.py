"""端到端联调脚本：验证 Java → Python 的 SSE 透传是否真的流式。

用法：python scripts/e2e_check.py
前置：pilot-server(8080) 与 pilot-agent(8000) 均已启动。
"""

import asyncio
import json
import time

import httpx

JAVA = "http://localhost:8080"


async def main() -> None:
    payload = {
        "message": "想给女朋友买个生日礼物，300以内",
        "sessionId": None,
        "slots": {"category": "礼物", "budget": "100-300", "scene": "生日"},
        "clientTraceId": "e2e-check",
    }

    print("→ POST /api/chat/stream")
    tokens: list[str] = []
    events: list[str] = []
    t0 = time.monotonic()
    first_token_at: float | None = None

    async with httpx.AsyncClient(timeout=60.0) as client:
        async with client.stream(
            "POST",
            f"{JAVA}/api/chat/stream",
            json=payload,
            headers={"Authorization": "Bearer dev-token"},
        ) as resp:
            print(f"  status={resp.status_code}")
            print(f"  X-Session-Id={resp.headers.get('x-session-id')}")
            print(f"  Content-Type={resp.headers.get('content-type')}")

            buf = ""
            async for chunk in resp.aiter_text():
                buf += chunk
                frames = buf.split("\n\n")
                buf = frames.pop()
                for frame in frames:
                    if not frame.strip():
                        continue
                    name = "message"
                    data = ""
                    for line in frame.split("\n"):
                        if line.startswith("event:"):
                            name = line[6:].strip()
                        elif line.startswith("data:"):
                            data += line[5:].strip()
                    if not data:
                        continue
                    events.append(name)
                    if name == "token":
                        if first_token_at is None:
                            first_token_at = time.monotonic() - t0
                        try:
                            tokens.append(json.loads(data).get("text", ""))
                        except json.JSONDecodeError:
                            pass
                    elif name in ("product_card", "slot_ask", "error", "done"):
                        print(f"  [{name}] {data[:160]}")

    total = time.monotonic() - t0
    print("\n=== 结果 ===")
    print(f"  事件序列: {events[:3]} … {events[-2:]}")
    print(f"  事件总数: {len(events)}")
    print(f"  token 数: {len(tokens)}")
    print(f"  首 token 延迟: {first_token_at:.2f}s" if first_token_at else "  未收到 token")
    print(f"  总耗时: {total:.2f}s")
    print(f"  拼接文本: {''.join(tokens)[:60]}…")

    ok = "product_card" in events and "done" in events and len(tokens) > 0
    print(f"\n{'PASS' if ok else 'FAIL'}: 三端流式链路{'正常' if ok else '异常'}")


if __name__ == "__main__":
    asyncio.run(main())
