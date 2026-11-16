"""请求级运行时上下文。

为什么用 contextvar 而不是把参数一路透传：
工具函数是被模型调用的，签名由 JSON Schema 决定，
不能为了拿 userId 就在每个工具里加一个参数——那会污染给模型看的工具定义。

contextvar 在这里是正确工具：它随 asyncio 任务自动隔离，
每个请求一条链，互不串味。
"""

from __future__ import annotations

from contextvars import ContextVar
from dataclasses import dataclass, field
from typing import Any

current_user_id: ContextVar[int | None] = ContextVar("current_user_id", default=None)
current_trace_id: ContextVar[str] = ContextVar("current_trace_id", default="")


@dataclass
class ToolArtifact:
    """工具产生的结构化副作用，走 SSE 旁路给前端，不占模型上下文。"""

    products: list[dict[str, Any]] = field(default_factory=list)
    tool_calls: list[dict[str, Any]] = field(default_factory=list)


current_artifact: ContextVar[ToolArtifact | None] = ContextVar("current_artifact", default=None)


def get_artifact() -> ToolArtifact:
    art = current_artifact.get()
    if art is None:
        art = ToolArtifact()
        current_artifact.set(art)
    return art
