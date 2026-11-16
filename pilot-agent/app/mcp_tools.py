"""MCP 工具接入。

Tavily 官方 MCP server 通过 stdio 提供 tavily_search / extract / crawl / map / research。
这里做三件事：

1. 建立 stdio 会话（子进程），把 MCP tool 列表拉回来。
2. 把 MCP tool 转成 LangChain 的 StructuredTool，让模型能直接 bind_tools。
3. 处理 Windows 下的启动差异（npx 是 .cmd，必须 cmd /c）和生命周期。

为什么不直接用 langchain-mcp-adapters：
多一个依赖，且它对 session 生命周期的封装和我们的"按需懒加载 + 失败降级"策略不完全一致。
自己写这层约 100 行，行为完全可控，出问题能看懂。

关键设计：MCP 是**可选增强**。
MCP server 拉不起来（没装 node、没网、key 失效）时，Agent 必须能继续跑，
只是少一个联网工具。所以初始化失败只降级不抛异常。
"""

from __future__ import annotations

import asyncio
import logging
import os
import sys
from typing import Any

from langchain_core.tools import StructuredTool
from pydantic import Field, create_model

from app.config import get_settings

logger = logging.getLogger("pilot.mcp")

# 只暴露真正需要的两个。crawl / map / research 对导购场景是过度设计，
# 全部塞给模型只会增加误调用概率。
EXPOSED_TOOLS = {"tavily_search", "tavily_extract"}

# 工具描述改写。MCP 原始描述是通用的"搜索网页"，
# 模型会拿它当万能工具。这里收紧成导购场景的边界。
TOOL_DESCRIPTIONS = {
    "tavily_search": (
        "联网搜索实时信息。仅在以下情况使用："
        "(1) 用户询问商品之外的信息，如品牌口碑、行业趋势、新品发布；"
        "(2) 站内商品库覆盖不到的品类，需要外部行情佐证。"
        "不要用它代替站内商品检索。query 用简洁的中文关键词。"
    ),
    "tavily_extract": (
        "抓取指定网页的正文内容。当用户给出一个链接、或需要核实某条信息的具体细节时使用。"
    ),
}


def _json_schema_to_model(tool_name: str, schema: dict[str, Any]) -> type:
    """把 MCP 的 JSON Schema 转成 pydantic 模型。

    只处理基础类型（string/number/integer/boolean/array），
    MCP 工具的入参基本都是这个层级，嵌套对象直接当 dict 透传。
    """
    props = (schema or {}).get("properties", {}) or {}
    required = set((schema or {}).get("required", []) or [])

    type_map = {
        "string": str,
        "number": float,
        "integer": int,
        "boolean": bool,
        "array": list,
        "object": dict,
    }

    fields: dict[str, Any] = {}
    for name, spec in props.items():
        py_type = type_map.get(spec.get("type", "string"), str)
        desc = spec.get("description", "")
        default = spec.get("default", ...)
        if name in required and default is ...:
            fields[name] = (py_type, Field(..., description=desc))
        else:
            # 非必填给 None 默认，避免模型被迫编造参数
            if default is ...:
                fields[name] = (py_type | None, Field(None, description=desc))
            else:
                fields[name] = (py_type, Field(default, description=desc))

    return create_model(f"MCP_{tool_name}", **fields)  # type: ignore[call-overload]


class McpToolkit:
    """管理 MCP 子进程会话，并把工具转成 LangChain 工具。"""

    def __init__(self) -> None:
        self._tools: list[StructuredTool] = []
        self._ready = False
        self._error: str | None = None
        self._lock = asyncio.Lock()

    @property
    def ready(self) -> bool:
        return self._ready

    @property
    def error(self) -> str | None:
        return self._error

    @property
    def tools(self) -> list[StructuredTool]:
        return self._tools

    def _server_params(self):
        from mcp import StdioServerParameters

        s = get_settings()
        env = dict(os.environ)
        env["TAVILY_API_KEY"] = s.tavily_api_key

        cmd = s.tavily_mcp_command
        args = s.mcp_args_list
        # Windows：npx 实际是 npx.cmd，CreateProcess 不能直接拉起 .cmd
        if sys.platform == "win32" and cmd in {"npx", "npm", "node"}:
            cmd, args = "cmd", ["/c", s.tavily_mcp_command, *args]

        return StdioServerParameters(command=cmd, args=args, env=env)

    async def _call_mcp(self, tool_name: str, arguments: dict[str, Any]) -> str:
        """每次调用建立短连接。

        长连接更省，但 MCP 子进程在 Windows 上容易因父进程重启而变成僵尸，
        且 Tavily 调用频率不高（一轮对话最多一两次），短连接的稳定性收益更大。
        """
        from mcp import ClientSession
        from mcp.client.stdio import stdio_client

        s = get_settings()
        async with stdio_client(self._server_params()) as (read, write):
            async with ClientSession(read, write) as session:
                await asyncio.wait_for(session.initialize(), timeout=s.mcp_timeout_seconds)
                result = await asyncio.wait_for(
                    session.call_tool(tool_name, arguments), timeout=s.mcp_timeout_seconds
                )

        if getattr(result, "is_error", False):
            texts = [getattr(c, "text", "") for c in (result.content or [])]
            raise RuntimeError(f"MCP tool error: {' '.join(texts)[:300]}")

        parts: list[str] = []
        for c in result.content or []:
            text = getattr(c, "text", None)
            if text:
                parts.append(text)
        return "\n".join(parts) if parts else "（工具返回空内容）"

    async def ainit(self) -> None:
        """拉取工具列表并包装。失败只记日志，不抛。"""
        async with self._lock:
            if self._ready:
                return
            s = get_settings()
            if not s.mcp_enabled:
                self._error = "MCP_ENABLED=false"
                logger.info("mcp: 已禁用")
                return
            if not s.tavily_api_key:
                self._error = "TAVILY_API_KEY 未配置"
                logger.warning("mcp: %s", self._error)
                return

            try:
                from mcp import ClientSession
                from mcp.client.stdio import stdio_client

                async with stdio_client(self._server_params()) as (read, write):
                    async with ClientSession(read, write) as session:
                        await asyncio.wait_for(
                            session.initialize(), timeout=s.mcp_timeout_seconds
                        )
                        listed = await asyncio.wait_for(
                            session.list_tools(), timeout=s.mcp_timeout_seconds
                        )

                tools: list[StructuredTool] = []
                for t in listed.tools:
                    if t.name not in EXPOSED_TOOLS:
                        continue
                    schema = getattr(t, "input_schema", None) or {}
                    args_model = _json_schema_to_model(t.name, schema)

                    async def _run(_name: str = t.name, **kwargs: Any) -> str:
                        payload = {k: v for k, v in kwargs.items() if v is not None}
                        return await self._call_mcp(_name, payload)

                    tools.append(
                        StructuredTool.from_function(
                            coroutine=_run,
                            name=t.name,
                            description=TOOL_DESCRIPTIONS.get(t.name, t.description or t.name),
                            args_schema=args_model,
                        )
                    )

                self._tools = tools
                self._ready = True
                logger.info("mcp: 就绪，工具=%s", [t.name for t in tools])
            except Exception as exc:  # noqa: BLE001 - 降级不阻断
                self._error = str(exc)[:300]
                self._ready = False
                logger.warning("mcp: 初始化失败(%s)，联网工具不可用", self._error)


mcp_toolkit = McpToolkit()
