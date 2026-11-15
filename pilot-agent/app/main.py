"""FastAPI 入口。

职责边界：只做协议转换（HTTP → Agent 事件流 → SSE），不含业务逻辑。
不解析 JWT，用户身份完全信任 Java 传来的 X-User-Id。
"""

import json
import logging

from fastapi import FastAPI, Header, HTTPException
from fastapi.responses import StreamingResponse

from app.agent import run_agent
from app.config import get_settings
from app.mcp_tools import mcp_toolkit
from app.memory import memory_store
from app.rag import knowledge_base
from app.schemas import AgentChatRequest

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s [%(name)s] %(message)s",
)

logger = logging.getLogger("pilot")

app = FastAPI(title="ShopPilot Agent", version="0.2.0")


def _verify_internal(token: str | None) -> None:
    if token != get_settings().internal_token:
        raise HTTPException(status_code=401, detail="invalid internal token")


@app.on_event("startup")
async def _warmup() -> None:
    """启动预热：RAG 索引 + MCP 工具列表。

    放在启动时做，避免首个用户请求承担 3~5 秒初始化延迟。
    两者失败都只记日志，服务照常启动（降级可用）。
    """
    logger.info("warmup: 开始预热")
    try:
        await knowledge_base.aload()
    except Exception as exc:  # noqa: BLE001
        logger.warning("warmup: RAG 失败 %s", exc)
    try:
        await mcp_toolkit.ainit()
    except Exception as exc:  # noqa: BLE001
        logger.warning("warmup: MCP 失败 %s", exc)
    logger.info(
        "warmup: 完成，知识 %d 块，MCP %s",
        knowledge_base.size,
        "就绪" if mcp_toolkit.ready else "不可用",
    )


@app.get("/agent/health")
async def health() -> dict:
    s = get_settings()
    return {
        "status": "ok",
        "version": "0.2.0",
        "model": s.llm_model,
        "vlModel": s.vl_model,
        "embeddingModel": s.embedding_model,
        "rag": {"chunks": knowledge_base.size, "degraded": knowledge_base.degraded},
        "mcp": {
            "ready": mcp_toolkit.ready,
            "tools": [t.name for t in mcp_toolkit.tools],
            "error": mcp_toolkit.error,
        },
        "memory": await memory_store.health(),
    }


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
            "X-Session-Id": req.session_id,
        },
    )


@app.delete("/agent/session/{session_id}")
async def reset_session(
    session_id: str,
    x_internal_token: str | None = Header(default=None, alias="X-Internal-Token"),
) -> dict:
    """清空会话记忆。调试与「新建对话」用。"""
    _verify_internal(x_internal_token)
    await memory_store.reset(session_id)
    return {"code": 0, "message": "ok"}
