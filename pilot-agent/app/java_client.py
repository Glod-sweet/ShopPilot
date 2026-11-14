"""Java 内部接口客户端。

所有工具的数据来源。Python 不直连数据库，这是硬约束。
"""

import httpx

from app.config import get_settings


class JavaClient:
    def __init__(self) -> None:
        s = get_settings()
        self._base = s.java_base_url
        self._headers = {"X-Internal-Token": s.internal_token}

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
        async with httpx.AsyncClient(timeout=5.0) as client:
            resp = await client.post(
                f"{self._base}/internal/products/search", json=payload, headers=self._headers
            )
            resp.raise_for_status()
            body = resp.json()
            if body.get("code") != 0:
                raise RuntimeError(f"search failed: {body.get('message')}")
            return body["data"].get("products", [])

    async def recent_orders(self, user_id: int) -> list[dict]:
        async with httpx.AsyncClient(timeout=5.0) as client:
            resp = await client.get(
                f"{self._base}/internal/orders/recent",
                params={"userId": user_id},
                headers=self._headers,
            )
            resp.raise_for_status()
            body = resp.json()
            if body.get("code") != 0:
                raise RuntimeError(f"orders failed: {body.get('message')}")
            return body["data"].get("orders", [])


java_client = JavaClient()
