"""FastAPI 入口。

职责边界：只做协议转换（HTTP → Agent 事件流 → SSE），不含任何业务逻辑。
不解析 JWT，用户身份完全信任 Java 传来的 X-User-Id。
"""

import json

from fastapi import FastAPI, Header, HTTPException
from fastapi.responses import StreamingResponse

from app.agent import run_agent
from app.config import get_settings
from app.schemas import AgentChatRequest

app = FastAPI(title="ShopPilot Agent", version="0.1.0")


def _verify_internal(token: str | None) -> None:
    if token != get_settings().internal_token:
        raise HTTPException(status_code=401, detail="invalid internal token")


@app.get("/agent/health")
async def health() -> dict:
    s = get_settings()
    return {"status": "ok", "version": "0.1.0", "model": s.llm_model}


@app.post("/agent/chat")
async def chat(
    req: AgentChatRequest,
    x_internal_token: str | None = Header(default=None, alias="X-Internal-Token"),
    x_user_id: int | None = Header(default=None, alias="X-User-Id"),
) -> StreamingResponse:
    _verify_internal(x_internal_token)

    # 身份以 Java 传入为准，不信任请求体里的 userId
    if x_user_id is not None:
        req = req.model_copy(update={"user_id": x_user_id})

    async def event_stream():
        async for evt in run_agent(req):
            payload = json.dumps(evt.data, ensure_ascii=False)
            yield f"event: {evt.type}\ndata: {payload}\n\n"

    return StreamingResponse(
        event_stream(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "X-Accel-Buffering": "no",
            "Connection": "keep-alive",
        },
    )
