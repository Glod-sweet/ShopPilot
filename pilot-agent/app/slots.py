"""槽位抽取与追问。

为什么需要这一层：
契约里定义了 `slot_ask` 事件，前端也实现了「渲染选项 → 用户点击 → 回填槽位」的完整链路，
但如果 Agent 从不产出这个事件，整条链路就是死的。用户说「想买个生日礼物」，
Agent 会直接搜一堆乱七八糟的商品回来——这正是 handbook 4.4 要避免的情况。

设计取舍：
- **规则抽取，不用模型**。槽位只有三个字段，且形态高度收敛（预算就是数字 + 区间词，
  品类就是品类词表）。用正则和词表比让模型抽取快一个数量级，且结果确定、可测试。
  模型抽取适合开放式信息抽取，这里不是。
- **必填才追问**。category + budget 缺一个就追问，scene 选填不追问。
  追问太频繁会把用户赶走，所以只问最关键的、且一次只问一个。
- **追问前置**。缺槽位时不进 ReAct 循环，直接产出 slot_ask + done。
  这样省掉一次完整模型调用，也避免模型「自作聪明」地先搜再问。
"""

from __future__ import annotations

import re

# 必填槽位。缺任何一个都要先追问，不执行检索（handbook 4.4）
REQUIRED_SLOTS = ("category", "budget")

# 品类词表：用户说的词 → 检索时用的品类标识。
# 只收常见导购场景里出现的说法，不做穷举——命不中就走场景关键词，不阻断。
CATEGORY_WORDS: dict[str, tuple[str, ...]] = {
    "蓝牙耳机": ("耳机", "降噪耳机", "无线耳机", "蓝牙"),
    "智能手表": ("手表", "智能手表", "手环"),
    "移动电源": ("充电宝", "移动电源", "快充"),
    "机械键盘": ("键盘", "机械键盘"),
    "口红": ("口红", "唇膏", "唇釉"),
    "香水": ("香水", "香氛"),
    "香薰": ("香薰", "蜡烛", "扩香"),
    "护肤套装": ("护肤品", "护肤", "面霜", "水乳"),
    "衣物清洁": ("洗衣液", "洗衣", "清洁剂"),
    "保温杯": ("保温杯", "水杯", "杯子"),
    "四件套": ("四件套", "床品", "被套"),
    "跑鞋": ("跑鞋", "运动鞋", "跑步鞋"),
    "露营装备": ("露营", "帐篷", "睡袋", "户外"),
    "运动水壶": ("水壶", "运动水壶"),
    "项链": ("项链", "饰品", "首饰"),
}

# 泛品类词：用户说得比品类名更抽象，靠场景映射去命中
GENERIC_CATEGORY_WORDS: dict[str, tuple[str, ...]] = {
    "礼物": ("礼物", "礼品", "送人", "生日"),
    "数码": ("数码", "电子产品"),
    "家居": ("家居", "居家", "家用"),
    "美妆": ("美妆", "化妆"),
    "户外": ("户外", "露营", "运动"),
}

# 预算解析：支持「300 以内」「100-300」「500 以上」「预算三百」等常见说法
_CN_NUM = {"零": 0, "一": 1, "二": 2, "两": 2, "三": 3, "四": 4, "五": 5,
           "六": 6, "七": 7, "八": 8, "九": 9, "十": 10}

_BUDGET_PATTERNS = (
    # 100-300 / 100~300 / 100到300
    re.compile(r"(\d+)\s*[-~到至]\s*(\d+)"),
    # 不超过300 / 不到300 / 低于300 —— 前置限定词，没有后置后缀
    # 必须放在带后缀的规则前面，否则「不超过500」会被后面的裸数字规则误判
    re.compile(r"(?:不超过|不到|低于|少于|最多)\s*(\d+)"),
    # 300 以内 / 300以下 / 不超过300（带后缀形式）
    re.compile(r"(?:不超过|不到|低于|少于)?\s*(\d+)\s*(?:元|块|块钱)?\s*(?:以内|以下|之内|左右)"),
    # 500 以上 / 高于500
    re.compile(r"(?:高于|超过|大于|至少)\s*(\d+)|(\d+)\s*(?:元|块|块钱)?\s*(?:以上|起)"),
    # 预算300 / 预算 300 元
    re.compile(r"预算\s*(?:是|在|大概|大约)?\s*(\d+)"),
    # 裸数字 + 元/块
    re.compile(r"(\d+)\s*(?:元|块|块钱)"),
)


def _cn_to_int(text: str) -> int | None:
    """把「三百」「两百五」这类中文数字转成 int。只处理常见的百位以内说法。"""
    if not text:
        return None
    # 先处理阿拉伯数字混写，如「300」
    if text.isdigit():
        return int(text)

    total = 0
    section = 0
    for ch in text:
        if ch in _CN_NUM:
            section = _CN_NUM[ch]
        elif ch == "百":
            section = (section or 1) * 100
            total += section
            section = 0
        elif ch == "千":
            section = (section or 1) * 1000
            total += section
            section = 0
        elif ch == "万":
            total = (total + section) * 10000
            section = 0
    total += section
    return total or None


def extract_category(text: str) -> str | None:
    """从用户输入里抽品类。先精确品类词，再泛品类词。"""
    if not text:
        return None
    for name, aliases in CATEGORY_WORDS.items():
        if any(a in text for a in aliases):
            return name
    for name, aliases in GENERIC_CATEGORY_WORDS.items():
        if any(a in text for a in aliases):
            return name
    return None


def extract_budget(text: str) -> str | None:
    """抽预算，归一成「300以内」「100-300」「500以上」三种契约认可的说法。"""
    if not text:
        return None

    for pattern in _BUDGET_PATTERNS:
        m = pattern.search(text)
        if not m:
            continue
        groups = m.groups()
        if len(groups) == 2 and groups[0] and groups[1]:
            return f"{groups[0]}-{groups[1]}"
        # 「高于500」和「500以上」两种写法的分组位置不同，取非空的那个
        num = groups[0] or (groups[1] if len(groups) > 1 else None)
        if num:
            matched = m.group(0)
            # 判断是区间下限还是上限。
            # 注意「不超过500」里的「超过」是上限语义，不能算下限，
            # 所以先排除「不超过/不到/低于/少于」，再判断下限词。
            is_upper = any(w in matched for w in ("以内", "以下", "之内", "左右"))
            if not is_upper and any(w in matched for w in ("不超过", "不到", "低于", "少于", "最多")):
                is_upper = True
            if not is_upper and any(w in matched for w in ("以上", "起", "高于", "超过", "大于", "至少")):
                return f"{num}以上"
            return f"{num}以内"

    # 兜底：中文数字 + 元
    m = re.search(r"([一二两三四五六七八九十百千]+)\s*(?:元|块|块钱)", text)
    if m:
        n = _cn_to_int(m.group(1))
        if n:
            return f"{n}以内"
    return None


def extract_scene(text: str) -> str | None:
    """抽场景。选填槽位，抽不到就算了，不追问。"""
    if not text:
        return None
    scenes = {
        "生日": ("生日",),
        # 「送X」是个开放集合（女朋友/男朋友/妈妈/闺蜜/领导…），
        # 逐个枚举永远漏，所以用「送」字 + 常见对象词的组合判断
        "送礼": (
            "礼物", "送礼", "送人", "送女友", "送男友", "送妈妈", "送爸爸",
            "送女朋友", "送男朋友", "送老婆", "送老公", "送闺蜜", "送朋友",
            "送长辈", "送领导", "送同事", "给女朋友", "给男朋友", "给妈妈", "给爸爸",
        ),
        "露营": ("露营", "野营", "户外"),
        "通勤": ("通勤", "上班", "地铁"),
        "运动": ("跑步", "健身", "运动"),
        "自用": ("自己用", "自用", "给自己"),
    }
    for code, words in scenes.items():
        if any(w in text for w in words):
            return code
    # 兜底：只要出现「送/给 + 人」的结构就算送礼意图。
    # 放在词表之后，避免「送自己」被误判成送礼（上面已由「自用」拦截）。
    if re.search(r"[送给]\s*\S{0,4}(朋友|女友|男友|妈|爸|姐|妹|哥|弟|长辈|领导|同事|闺蜜)", text):
        return "送礼"
    return None


def fill_slots(text: str, existing: dict[str, str | None]) -> dict[str, str | None]:
    """把本轮输入里抽到的槽位合并进已有槽位。

    合并而非覆盖：用户这轮只说了预算，上一轮说的品类不能被清空。
    """
    slots = dict(existing or {})
    cat = extract_category(text)
    if cat:
        slots["category"] = cat
    budget = extract_budget(text)
    if budget:
        slots["budget"] = budget
    scene = extract_scene(text)
    if scene:
        slots["scene"] = scene
    return slots


def missing_required(slots: dict[str, str | None] | None) -> list[str]:
    """返回缺失的必填槽位，按追问优先级排序。"""
    s = slots or {}
    return [k for k in REQUIRED_SLOTS if not s.get(k)]


# 「再来一单」类意图：这类请求不需要品类和预算，直接查订单历史即可。
# 如果不排除，用户说「上次买的洗衣液再来一单」会被追问「大概预算多少」——
# 而他根本不想重新挑，只想把买过的东西再买一次。
REORDER_SIGNALS = (
    "再来一单", "再来一次", "再买一单", "再买一次", "和上次一样", "跟上次一样",
    "上次买", "之前买", "重复购买", "补货",
)


def is_reorder_intent(text: str) -> bool:
    """判断是否为「再来一单」类意图。这类请求跳过槽位追问。"""
    if not text:
        return False
    return any(sig in text for sig in REORDER_SIGNALS)


# 回指/追问类语句：用户在问「我刚才说的」「这个怎么样」「哪个好」，
# 指的是上文已经建立的需求。这类话本身不含品类词，但绝不是「新需求」，
# 拿它去追问品类会把用户正在进行的对话打断。
# 典型翻车场景：用户说「想买露营帐篷，预算500」，接着问「我刚才说预算多少」，
# 结果被反问「想买哪一类呢」——用户会觉得这助手失忆了。
ANAPHORA_SIGNALS = (
    "我刚才", "我刚刚", "我前面", "我之前说", "上面说", "刚说的", "说的那个",
    "这个", "那个", "它", "这两款", "那两款", "哪个", "哪一个", "怎么样",
    "多少钱", "贵不贵", "值不值", "还有别的", "换一个", "再看看",
)


def is_followup_question(text: str) -> bool:
    """判断是否为针对上文已有需求的追问/回指，而非提出新需求。

    这类语句要放行给模型，不能走槽位追问。
    """
    if not text:
        return False
    return any(sig in text for sig in ANAPHORA_SIGNALS)


# 追问话术与选项。文案要口语化，选项要覆盖大多数情况，
# 让用户「点一下就行」比让他打字转化率高得多。
QUESTIONS: dict[str, tuple[str, list[str]]] = {
    "category": (
        "想买哪一类呢？我按品类帮您筛",
        ["蓝牙耳机", "香薰礼盒", "口红", "项链", "保温杯", "露营装备"],
    ),
    "budget": (
        "大概预算多少？我好帮您卡住价位",
        ["100以内", "100-300", "300-500", "500以上"],
    ),
}


def build_slot_ask(slot: str) -> dict:
    """构造 slot_ask 事件的 data。字段名与 api-contract.md 4.2 一致。"""
    question, options = QUESTIONS.get(slot, (f"请补充{slot}", []))
    return {"slot": slot, "question": question, "options": options}
