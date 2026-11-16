"""场景化推荐理由生成。

为什么需要这一层：
`product_card.reason` 是 api-contract.md 里标注的必填字段，handbook 也明确说
「这是本项目区别于普通商品列表的核心」。但 Java 侧的 `buildReason()` 只能拼出
「场景表里人工标注的理由」或「副标题」——它对所有用户都是同一句话，与用户实际说的
需求无关。用户问「送女朋友生日礼物」，Java 给的可能是「42dB 降噪，通勤地铁安静」，
这就露馅了：这理由跟「送女朋友」毫无关系。

所以真正的场景化理由必须由模型针对**这一句用户输入**重写。

三个工程约束：
1. **一次调用搞定全部商品**，不是每件一次。3 件商品调 3 次 = 3 倍延迟 + 3 倍成本，
   而模型完全有能力在一个响应里给 3 条理由。
2. **延迟必须可控**。这是推荐链路上的额外一次往返，目标 1.5 秒内。
   超时就放弃，用 Java 的兜底文案 —— 用户不会因为理由不够个性化就流失，
   但会因为没有理由而失去信任感。
3. **失败必须降级，不能抛异常**。理由生成失败不该让整条检索挂掉。
"""

from __future__ import annotations

import asyncio
import json
import logging
import re

from langchain_core.messages import HumanMessage, SystemMessage

from app.config import get_settings
from app.llm import get_chat_model

logger = logging.getLogger("pilot.reason")

# 理由生成是推荐链路上的附加环节，超时即放弃。
# 取值依据（实测 qwen-plus，含网络往返）：
#   1 件商品 ≈ 2.2s（输出 82 字）
#   3 件商品 ≈ 4.0s（输出 232 字）
# 耗时主要花在输出 token 上，不是网络。所以阈值必须按「最多 3 件」来定。
# 5 秒能覆盖 3 件商品，且卡片在工具返回时就已推给前端，
# 用户看到的是「卡片先出来，推荐语随后补上」，不会有 5 秒空白。
REASON_TIMEOUT_SECONDS = 5.0

# 只给前 3 件写理由：一次推 3 款是 handbook 的硬要求（「推太多等于没推」），
# 而且 3 件是输出长度与延迟的平衡点 —— 5 件要 6 秒以上，得不偿失。
MAX_PRODUCTS = 3

# 提示词要求模型只输出 JSON，但模型偶尔会加解释或 ```json 包裹，需要容错
SYSTEM_PROMPT = """你是电商导购的文案助手。为给定商品各写一句推荐理由。

要求：
1. 每条理由必须落到用户**这句话里的具体需求**上，不能泛泛而谈。
   反例：「品质优良，值得购买」「性价比高」。
   正例（用户说「送女朋友生日礼物，300以内」）：「268元带礼盒和贺卡，开箱有仪式感，送女友不用另买包装」。
2. 每条 15~30 字，一句话说完。**越短越好**，不要分点、不要换行。
3. 只能使用给定商品信息里的数字和事实（价格、材质、参数）。**绝不编造**未给出的功能、赠品、优惠。
4. 如果某件商品与用户需求关系较弱，如实说明它的局限，不要硬夸。
5. 只输出 JSON 数组，格式：[{"id": 商品ID, "reason": "理由"}]
   不要输出任何其他文字，不要用 markdown 代码块包裹。"""


def _build_user_prompt(query: str, slots: dict, products: list[dict]) -> str:
    """把用户需求与商品信息拼成一次调用的输入。"""
    slot_bits = []
    if slots:
        if slots.get("category"):
            slot_bits.append(f"品类={slots['category']}")
        if slots.get("budget"):
            slot_bits.append(f"预算={slots['budget']}")
        if slots.get("scene"):
            slot_bits.append(f"场景={slots['scene']}")

    lines = [f"用户原话：{query}"]
    if slot_bits:
        lines.append("已识别需求：" + "，".join(slot_bits))
    lines.append("")
    lines.append("商品列表：")
    for i, p in enumerate(products, 1):
        bits = [
            f"id={p.get('productId')}",
            f"标题={p.get('title')}",
            f"价格={p.get('price')}元",
        ]
        if p.get("subTitle"):
            bits.append(f"副标题={p['subTitle']}")
        if p.get("tags"):
            bits.append(f"标签={'/'.join(p['tags'][:4])}")
        if p.get("rating"):
            bits.append(f"评分={p['rating']}")
        if p.get("salesCount"):
            bits.append(f"销量={p['salesCount']}")
        if p.get("stock") is not None:
            bits.append(f"库存={p['stock']}")
        lines.append(f"{i}. " + " | ".join(bits))

    lines.append("")
    lines.append(f"请为以上 {len(products)} 件商品各写一句推荐理由，输出 JSON 数组。")
    return "\n".join(lines)


def _extract_json(text: str) -> list[dict]:
    """从模型输出里抠出 JSON 数组。

    模型常见的三种污染形式，都要能处理：
      - 前后带解释文字
      - 被 ```json ... ``` 包裹
      - 输出成 {"items": [...]} 而不是裸数组
    """
    if not text:
        return []

    s = text.strip()

    # 去掉 markdown 代码块
    if s.startswith("```"):
        s = re.sub(r"^```[a-zA-Z]*\s*", "", s)
        s = re.sub(r"\s*```$", "", s)
        s = s.strip()

    # 直接是数组
    if s.startswith("["):
        try:
            data = json.loads(s)
            return data if isinstance(data, list) else []
        except json.JSONDecodeError:
            pass

    # 从文本里截取第一个 [ ... ] 片段
    start = s.find("[")
    end = s.rfind("]")
    if start >= 0 and end > start:
        try:
            data = json.loads(s[start : end + 1])
            return data if isinstance(data, list) else []
        except json.JSONDecodeError:
            pass

    # 包了一层对象
    if s.startswith("{"):
        try:
            obj = json.loads(s)
            for key in ("items", "data", "products", "result"):
                v = obj.get(key)
                if isinstance(v, list):
                    return v
        except json.JSONDecodeError:
            pass

    return []


async def rewrite_reasons(
    query: str,
    products: list[dict],
    slots: dict | None = None,
) -> list[dict]:
    """为商品重写场景化推荐理由。

    返回新的商品列表（原地补充 reason）。任何环节失败都返回原列表，
    保证调用方永远拿到可用数据。

    Args:
        query: 用户原话，理由必须针对它
        products: 商品卡片列表，需含 productId / title / price
        slots: 已识别的槽位，帮助模型理解需求
    """
    if not products:
        return products

    # 只处理前 N 件：工具侧最多返回 5 件，超出部分模型也写不过来
    target = products[:MAX_PRODUCTS]
    if not target:
        return products

    s = get_settings()
    if not s.llm_api_key:
        logger.info("reason: 未配置 LLM_API_KEY，保留 Java 兜底理由")
        return products

    user_prompt = _build_user_prompt(query, slots or {}, target)

    try:
        # 用主模型而不是单独配一个小模型：理由质量直接决定用户对推荐的可信度，
        # 这是核心体验，不值得为省几百 token 换成更弱的模型。
        #
        # 超时用 asyncio.wait_for 而不是 config={"timeout": ...}：
        # 后者在 ChatOpenAI 上会触发底层重试（max_retries=2），
        # 实测 3 件商品花了 4.4 秒 —— 超时后重试两次，等于 3 倍延迟。
        # wait_for 是硬截断，超了立即放弃，符合「附加环节不拖累主链路」的定位。
        model = get_chat_model()
        coro = model.ainvoke(
            [
                SystemMessage(content=SYSTEM_PROMPT),
                HumanMessage(content=user_prompt),
            ]
        )
        resp = await asyncio.wait_for(coro, timeout=REASON_TIMEOUT_SECONDS)
        text = resp.text or str(resp.content)
        items = _extract_json(text)

        if not items:
            logger.warning("reason: 模型输出无法解析，保留原理由。原文=%s", text[:120])
            return products

        # 按 productId 建索引，避免依赖模型输出的顺序
        by_id: dict[int, str] = {}
        for it in items:
            if not isinstance(it, dict):
                continue
            pid = it.get("id") or it.get("productId") or it.get("product_id")
            reason = it.get("reason") or it.get("text")
            if pid is None or not reason:
                continue
            try:
                by_id[int(pid)] = str(reason).strip()
            except (TypeError, ValueError):
                continue

        if not by_id:
            logger.warning("reason: 解析到 JSON 但没有有效条目")
            return products

        rewritten = 0
        out: list[dict] = []
        for p in products:
            item = dict(p)
            pid = item.get("productId")
            if pid is not None:
                new_reason = by_id.get(int(pid))
                # 空字符串或过短的理由不如原来的兜底文案，直接丢弃
                if new_reason and len(new_reason) >= 8:
                    item["reason"] = new_reason
                    item["_reasonBy"] = "llm"
                    rewritten += 1
                else:
                    item["_reasonBy"] = "java"
            out.append(item)

        logger.info("reason: 重写 %d/%d 条理由", rewritten, len(products))
        return out

    except Exception as exc:  # noqa: BLE001 - 理由生成失败不能影响推荐
        logger.warning("reason: 生成失败，保留 Java 兜底理由（%s: %s）", type(exc).__name__, exc)
        return products
