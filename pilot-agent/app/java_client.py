"""Java 内部接口客户端。

所有工具的数据来源。Python 不直连数据库，这是硬约束。
工具调用超时统一 5s——快失败好过拖死整条 SSE 流。
"""

from __future__ import annotations

import logging

import httpx

from app.config import get_settings

logger = logging.getLogger("pilot.java")

TOOL_TIMEOUT = 5.0


class JavaClient:
    def __init__(self) -> None:
        s = get_settings()
        self._base = s.java_base_url
        self._headers = {"X-Internal-Token": s.internal_token}

    async def _request(self, method: str, path: str, **kwargs) -> dict:
        async with httpx.AsyncClient(timeout=TOOL_TIMEOUT) as client:
            resp = await client.request(method, f"{self._base}{path}", headers=self._headers, **kwargs)
            resp.raise_for_status()
            body = resp.json()
            if body.get("code") != 0:
                raise RuntimeError(f"{path} failed: {body.get('message')}")
            return body.get("data") or {}

    async def search_products(
        self,
        query: str,
        category: str | None = None,
        max_price: float | None = None,
        min_price: float | None = None,
        limit: int = 5,
    ) -> list[dict]:
        payload = {
            "query": query,
            "category": category,
            "maxPrice": max_price,
            "minPrice": min_price,
            "limit": limit,
        }
        data = await self._request("POST", "/internal/products/search", json=payload)
        return data.get("products", [])

    async def get_product_detail(self, product_id: int) -> dict:
        return await self._request("GET", f"/internal/products/{product_id}")

    async def recent_orders(self, user_id: int) -> list[dict]:
        data = await self._request("GET", "/internal/orders/recent", params={"userId": user_id})
        return data.get("orders", [])

    async def get_user_profile(self, user_id: int) -> dict:
        return await self._request("GET", f"/internal/users/{user_id}/profile")


java_client = JavaClient()
