"""业务工具。

商品相关工具全部通过 Java 内部接口取数，Python 不碰数据库。

工具描述是给模型看的 API 文档，写得越具体，模型误调用越少。
这里每个 docstring 都明确写了"什么时候用"和"什么时候不要用"。

商品卡片通过 ToolArtifact 回传给编排层，而不是塞进 ToolMessage 的文本里——
模型不需要看完整 JSON（浪费 token 且容易照抄字段），
但前端需要结构化数据。两条通道分开走。
"""

from __future__ import annotations

import asyncio
import json
import logging

from langchain_core.tools import tool
from pydantic import BaseModel, Field

from app.java_client import java_client
from app.reason import rewrite_reasons

logger = logging.getLogger("pilot.tools")


from app.runtime import get_artifact


class SearchArgs(BaseModel):
    keyword: str = Field(description="搜索关键词，从用户需求中提炼，如「蓝牙耳机」「香薰礼盒」")
    category: str | None = Field(default=None, description="商品类目，如 gift/digital/home")
    max_price: float | None = Field(default=None, description="价格上限，单位元")
    min_price: float | None = Field(default=None, description="价格下限，单位元")
    limit: int = Field(default=3, description="返回条数，最多 5 条，默认 3")


@tool("search_products", args_schema=SearchArgs)
async def search_products(
    keyword: str,
    category: str | None = None,
    max_price: float | None = None,
    min_price: float | None = None,
    limit: int = 3,
) -> str:
    """在站内商品库检索真实商品。这是推荐商品的唯一数据来源。

    什么时候用：用户需要具体商品推荐时必调。
    什么时候不要用：用户只是闲聊、问规则、问常识时不要调。
    检索不到时：换更宽的关键词重试一次，仍为空则如实告知用户，绝不编造商品。
    """
    limit = max(1, min(limit, 5))
    try:
        products = await java_client.search_products(
            query=keyword,
            category=category,
            max_price=max_price,
            min_price=min_price,
            limit=limit,
        )
    except Exception as exc:  # noqa: BLE001 - 工具失败要如实告知，不能假装有数据
        logger.warning("search_products 失败: %s", exc)
        return f"商品检索接口暂时不可用（{type(exc).__name__}）。请告知用户稍后重试，不要编造商品。"

    if not products:
        return f"没有检索到与「{keyword}」匹配的商品。可以建议用户放宽价格或换个说法。"

    # 场景化理由：Java 给的 reason 是「场景表人工标注」或「副标题」，
    # 对所有用户都是同一句话，与用户这句需求无关。这里让模型针对用户原话重写。
    # 失败自动降级为 Java 原文，不影响推荐本身。
    products = await rewrite_reasons(
        query=keyword,
        products=products,
        slots={
            "category": category,
            "budget": f"{max_price}以内" if max_price else None,
        },
    )

    get_artifact().products = products

    # 给模型看的是精简摘要，不是原始 JSON。
    # 带上 reason 是因为模型要基于它组织话术 —— 但理由已在卡片里，
    # 这里只给前 30 字做提示，避免模型照抄整段。
    lines = [f"检索到 {len(products)} 件商品："]
    for i, p in enumerate(products, 1):
        title = p.get("title", "")
        price = p.get("price", "")
        stock = p.get("stock", 0)
        tags = "/".join(p.get("tags") or [])
        reason = (p.get("reason") or "")[:30]
        line = f"{i}. {title} | ¥{price} | 库存{stock} | {tags}"
        if reason:
            line += f" | 推荐角度：{reason}"
        lines.append(line)
    return "\n".join(lines)


class DetailArgs(BaseModel):
    product_id: int = Field(description="商品 ID，来自 search_products 的返回")


@tool("get_product_detail", args_schema=DetailArgs)
async def get_product_detail(product_id: int) -> str:
    """查询单个商品的详细参数。

    什么时候用：用户追问某款商品的具体参数、材质、尺寸、型号时才调。
    什么时候不要用：为了生成推荐理由而调——推荐理由应该基于 search_products 已有信息。
    """
    try:
        detail = await java_client.get_product_detail(product_id)
    except Exception as exc:  # noqa: BLE001
        logger.warning("get_product_detail 失败: %s", exc)
        return f"商品详情暂时取不到（{type(exc).__name__}）。请基于已有信息回答。"

    if not detail:
        return f"未找到 ID 为 {product_id} 的商品。"

    params = detail.get("params") or {}
    if isinstance(params, (dict, list)):
        params_text = json.dumps(params, ensure_ascii=False)
    else:
        params_text = str(params)

    return (
        f"商品：{detail.get('title', '')}\n"
        f"价格：¥{detail.get('price', '')}\n"
        f"库存：{detail.get('stock', '')}\n"
        f"参数：{params_text[:800]}\n"
        f"描述：{(detail.get('description') or '')[:400]}"
    )


class OrderArgs(BaseModel):
    limit: int = Field(default=3, description="返回最近几笔订单，默认 3")


@tool("get_order_history", args_schema=OrderArgs)
async def get_order_history(limit: int = 3) -> str:
    """查询当前用户最近的订单。

    什么时候用：用户说「再来一单」「上次买的那个」「和之前一样的」时必须先调这个，
    拿到具体商品再决定是否加购。不要凭空猜测用户买过什么。
    """
    from app.runtime import current_user_id

    uid = current_user_id.get()
    if not uid:
        return "无法确定当前用户，不能查询订单历史。"

    try:
        orders = await java_client.recent_orders(uid)
    except Exception as exc:  # noqa: BLE001
        logger.warning("get_order_history 失败: %s", exc)
        return f"订单查询暂时不可用（{type(exc).__name__}）。请让用户自己描述想再买的商品。"

    if not orders:
        return "该用户还没有历史订单。"

    lines = [f"最近 {min(len(orders), limit)} 笔订单："]
    for o in orders[:limit]:
        lines.append(
            f"- {o.get('productTitle', o.get('title', '未知商品'))} "
            f"x{o.get('quantity', 1)} | 下单 {o.get('createdAt', '')}"
        )
    return "\n".join(lines)


class CompareArgs(BaseModel):
    product_ids: list[int] = Field(
        description="要对比的商品 ID 列表，2~5 个。必须是用户明确指定或此前检索结果里的真实 ID"
    )


@tool("compare_products", args_schema=CompareArgs)
async def compare_products(product_ids: list[int]) -> str:
    """批量拉取多件商品的完整信息，用于横向对比。

    什么时候用：用户要求对比商品时（「这两款哪个好」「帮我比较一下」「哪个更值」），
    或用户从卡片里勾选了多件商品发起提问。

    什么时候不要用：只有一件商品时（那叫详情，用 get_product_detail）；
    用户还没选定具体商品时（先 search_products 让他挑）。

    返回的内容包含每件的价格、参数、库存、评分、销量、适用场景，
    你需要据此从质量、性价比、适配场景三个维度做取舍，并给出明确结论。
    """
    ids = [int(i) for i in product_ids if i is not None][:5]
    if len(ids) < 2:
        return "对比至少需要 2 件商品。只有 1 件时请用 get_product_detail 查看详情。"

    # 并发拉取：串行 5 件 × 每件约 80ms = 400ms，并发只要 100ms 左右。
    # return_exceptions=True 是为了「部分失败也能比」——
    # 5 件里有 1 件下架了，不该让整个对比失败。
    results = await asyncio.gather(
        *(java_client.get_product_detail(pid) for pid in ids),
        return_exceptions=True,
    )

    details: list[dict] = []
    failed: list[int] = []
    for pid, res in zip(ids, results, strict=True):
        if isinstance(res, BaseException) or not res:
            failed.append(pid)
            logger.warning("compare_products: 商品 %s 取详情失败", pid)
            continue
        details.append(res)

    if not details:
        return f"这几件商品的信息都取不到（ID: {ids}）。请告知用户稍后重试，不要编造参数。"

    if len(details) == 1:
        d = details[0]
        return (
            f"只成功取到 1 件商品的信息，无法对比。\n"
            f"商品：{d.get('title', '')} ¥{d.get('price', '')}\n"
            f"请告知用户其余商品暂时查不到，并基于这一件给建议。"
        )

    # 商品卡片走 artifact 旁路给前端
    get_artifact().products = [
        {
            "productId": d.get("id"),
            "title": d.get("title"),
            "subTitle": d.get("sub_title"),
            "price": float(d.get("price") or 0),
            "originalPrice": d.get("original_price"),
            "coverUrl": d.get("cover_url"),
            "stock": d.get("stock") or 0,
            "salesCount": d.get("sales_count") or 0,
            "rating": d.get("rating"),
            "tags": d.get("tags") or [],
            "reason": "",
        }
        for d in details
    ]

    return _format_comparison(details, failed)


def _format_comparison(details: list[dict], failed: list[int]) -> str:
    """把商品详情拼成模型能直接比较的结构化文本。

    为什么用「逐项并列」而不是「逐商品罗列」：
    模型做对比时是按维度横向看的（「价格上谁更便宜」「降噪上谁更强」），
    按商品罗列会迫使它自己在脑子里转置，容易漏项。
    """
    lines = [f"共 {len(details)} 件商品，逐项对比：", ""]

    # 基础信息表
    for i, d in enumerate(details, 1):
        lines.append(f"【商品{i}】{d.get('title', '')} (ID={d.get('id')})")
        bits = [f"价格 ¥{d.get('price', '?')}"]
        if d.get("original_price"):
            bits.append(f"原价 ¥{d['original_price']}")
        if d.get("rating"):
            bits.append(f"评分 {d['rating']}")
        if d.get("sales_count") is not None:
            bits.append(f"销量 {d['sales_count']}")
        stock = d.get("stock")
        if stock is not None:
            bits.append("有货" if int(stock) > 0 else "无货")
        lines.append("  " + " | ".join(bits))

        if d.get("sub_title"):
            lines.append(f"  副标题：{d['sub_title']}")

        tags = d.get("tags")
        if tags:
            lines.append(f"  标签：{'/'.join(tags[:6])}")

        params = d.get("params") or d.get("attributes")
        if params:
            if isinstance(params, dict):
                kv = "；".join(f"{k}={v}" for k, v in list(params.items())[:10])
            else:
                kv = str(params)[:300]
            lines.append(f"  参数：{kv}")

        desc = d.get("description") or d.get("selling_points")
        if desc:
            lines.append(f"  描述：{str(desc)[:200]}")
        lines.append("")

    # 给模型明确的对比维度提示，避免它只是复述参数表
    lines.append("请从以下维度比较并给出结论：")
    lines.append("1. 质量：材质/做工/核心参数，谁的硬指标更强")
    lines.append("2. 性价比：同等价位谁给得更多，或同等配置谁更便宜")
    lines.append("3. 适配场景：什么情况下该选哪一件")
    lines.append("最后必须明确推荐一件，并说明「什么情况下我会改推另一件」。")

    if failed:
        lines.append("")
        lines.append(f"注意：商品 {failed} 的信息取不到，不要对它们做任何评价。")

    return "\n".join(lines)


BUSINESS_TOOLS = [search_products, get_product_detail, get_order_history, compare_products]
