"""三端共用的数据契约。

这里定义的模型必须与 docs/api-contract.md 完全一致。
改这里之前先改契约文档，否则三端会不一致。
"""

from typing import Any, Literal

from pydantic import BaseModel, Field


class HistoryMessage(BaseModel):
    role: Literal["user", "assistant", "system"]
    content: str


class Slots(BaseModel):
    """槽位状态。None 表示未收集，Agent 据此决定是否追问。"""

    category: str | None = None
    budget: str | None = None
    scene: str | None = None


class AgentChatRequest(BaseModel):
    user_id: int = Field(alias="userId")
    session_id: str = Field(alias="sessionId")
    message: str
    history: list[HistoryMessage] = Field(default_factory=list)
    slots: Slots = Field(default_factory=Slots)
    trace_id: str | None = Field(default=None, alias="traceId")

    model_config = {"populate_by_name": True}


class ProductCard(BaseModel):
    product_id: int = Field(serialization_alias="productId")
    title: str
    sub_title: str | None = Field(default=None, serialization_alias="subTitle")
    price: float
    original_price: float | None = Field(default=None, serialization_alias="originalPrice")
    cover_url: str | None = Field(default=None, serialization_alias="coverUrl")
    stock: int = 0
    sales_count: int = Field(default=0, serialization_alias="salesCount")
    rating: float | None = None
    tags: list[str] = Field(default_factory=list)
    reason: str = ""

    model_config = {"populate_by_name": True}


class SseEvent(BaseModel):
    """SSE 事件统一结构。event 名与 docs/api-contract.md 第 4 节一致。"""

    type: Literal[
        "meta", "token", "tool_call", "tool_result", "product_card", "slot_ask", "done", "error"
    ]
    data: dict[str, Any]
