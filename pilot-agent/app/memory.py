"""会话记忆。

分两层，职责不同：

1. 短期记忆（Redis List）：原文存最近 N 轮对话，带 TTL。
   这是"记得上句话说了什么"。
2. 长期摘要（Redis String）：超过阈值后，把早期对话压成一段摘要。
   这是"记得这个用户大概想要什么"，防止上下文无限膨胀。

为什么不全塞给模型：Token 是要花钱的，而且长上下文会让模型注意力涣散——
20 轮之前的原话对当前决策几乎没有价值，摘要就够了。

为什么 Java 也存了 history，这里还要存：
Java 存的是"给用户看的聊天记录"，是业务数据；
这里存的是"给模型看的上下文"，是可压缩、可过期的运行态。
两者生命周期和形态都不同，不该合并。

降级策略：Redis 连不上就用进程内存。本地开发不该被中间件卡住，
但日志必须明确警告，避免线上误以为记忆生效了。
"""

from __future__ import annotations

import json
import logging
import time
from dataclasses import dataclass, field

from app.config import get_settings

logger = logging.getLogger("pilot.memory")


@dataclass
class Turn:
    role: str
    content: str
    ts: float = field(default_factory=time.time)

    def to_json(self) -> str:
        return json.dumps({"role": self.role, "content": self.content, "ts": self.ts}, ensure_ascii=False)


class MemoryStore:
    """会话记忆存储。Redis 优先，不可用则内存兜底。"""

    def __init__(self) -> None:
        self._redis = None
        self._memory: dict[str, list[str]] = {}
        self._summaries: dict[str, str] = {}
        self._init_redis()

    def _init_redis(self) -> None:
        url = get_settings().redis_url
        if not url:
            logger.warning("memory: REDIS_URL 为空，使用进程内存（重启即丢）")
            return
        try:
            import redis.asyncio as aioredis

            client = aioredis.from_url(
                url, encoding="utf-8", decode_responses=True, socket_connect_timeout=2, protocol=2
            )
            self._redis = client
            logger.info("memory: Redis 客户端已创建")
        except Exception as exc:  # noqa: BLE001 - 降级不阻断启动
            logger.warning("memory: Redis 初始化失败(%s)，降级为进程内存", exc)
            self._redis = None

    # ---- key 设计：pilot:mem:{session} / pilot:sum:{session} ----

    @staticmethod
    def _key(session_id: str) -> str:
        return f"pilot:mem:{session_id}"

    @staticmethod
    def _sum_key(session_id: str) -> str:
        return f"pilot:sum:{session_id}"

    async def append_turn(self, session_id: str, role: str, content: str) -> None:
        if not content:
            return
        item = Turn(role=role, content=content).to_json()
        ttl = get_settings().memory_ttl_seconds

        if self._redis is not None:
            try:
                key = self._key(session_id)
                await self._redis.rpush(key, item)
                await self._redis.expire(key, ttl)
                return
            except Exception as exc:  # noqa: BLE001
                logger.warning("memory: 写入 Redis 失败(%s)，本次落内存", exc)

        self._memory.setdefault(session_id, []).append(item)

    async def load_turns(self, session_id: str) -> list[Turn]:
        raw: list[str] = []
        if self._redis is not None:
            try:
                raw = await self._redis.lrange(self._key(session_id), 0, -1)
            except Exception as exc:  # noqa: BLE001
                logger.warning("memory: 读 Redis 失败(%s)", exc)
                raw = self._memory.get(session_id, [])
        else:
            raw = self._memory.get(session_id, [])

        turns: list[Turn] = []
        for item in raw:
            try:
                d = json.loads(item)
                turns.append(Turn(role=d["role"], content=d["content"], ts=d.get("ts", 0)))
            except Exception as exc:  # noqa: BLE001 - 脏数据跳过，不要毒死整条链
                logger.debug("memory: 跳过无法解析的记录 %s", exc)
                continue
        return turns

    async def get_summary(self, session_id: str) -> str:
        if self._redis is not None:
            try:
                return await self._redis.get(self._sum_key(session_id)) or ""
            except Exception as exc:  # noqa: BLE001
                logger.warning("memory: 读摘要失败(%s)，回落内存", exc)
        return self._summaries.get(session_id, "")

    async def set_summary(self, session_id: str, summary: str) -> None:
        ttl = get_settings().memory_ttl_seconds
        if self._redis is not None:
            try:
                await self._redis.set(self._sum_key(session_id), summary, ex=ttl)
                return
            except Exception as exc:  # noqa: BLE001
                logger.warning("memory: 写摘要失败(%s)，落内存", exc)
        self._summaries[session_id] = summary

    async def trim(self, session_id: str) -> None:
        """只保留最近 N 条原文，其余由调用方先摘要再丢弃。"""
        s = get_settings()
        keep = s.memory_keep_turns
        if self._redis is not None:
            try:
                key = self._key(session_id)
                total = await self._redis.llen(key)
                if total > s.memory_max_turns:
                    await self._redis.ltrim(key, total - keep, -1)
                return
            except Exception as exc:  # noqa: BLE001
                logger.warning("memory: 裁剪失败(%s)", exc)
        if session_id in self._memory and len(self._memory[session_id]) > s.memory_max_turns:
            self._memory[session_id] = self._memory[session_id][-keep:]

    async def reset(self, session_id: str) -> None:
        if self._redis is not None:
            try:
                await self._redis.delete(self._key(session_id), self._sum_key(session_id))
                return
            except Exception as exc:  # noqa: BLE001
                logger.warning("memory: 清空失败(%s)", exc)
        self._memory.pop(session_id, None)
        self._summaries.pop(session_id, None)

    async def health(self) -> dict:
        if self._redis is None:
            return {"backend": "memory", "ok": True}
        try:
            pong = await self._redis.ping()
            return {"backend": "redis", "ok": bool(pong)}
        except Exception as exc:  # noqa: BLE001
            return {"backend": "redis", "ok": False, "error": str(exc)}


memory_store = MemoryStore()
