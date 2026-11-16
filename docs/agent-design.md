# Agent 助手实现说明

> 本文说明 `pilot-agent` 这一层的设计。技术栈：FastAPI + LangChain 1.x + LangGraph + 阿里云百炼（千问）+ Tavily MCP + Redis。

## 一、这一层解决什么问题

Java 层解决"数据与事务"，Agent 层解决"理解与决策"。用户的模糊意图（「想给女朋友买个生日礼物，300 以内」）在这里被翻译成具体的商品检索、对比、推荐理由。

职责边界不变：**Python 不连数据库、不解析 JWT**。所有业务数据通过 Java 内部接口取，身份由 `X-User-Id` 传入。

## 二、模块划分

```
app/
├── config.py       配置（密钥只从 .env 读）
├── llm.py          模型工厂：chat / vl / embed
├── prompts.py      系统提示词（静态规则 + 动态拼装）
├── middleware.py   中间件：上下文裁剪 / 审计 / 工具重试
├── memory.py       会话记忆：Redis 短期 + 摘要压缩
├── rag.py          RAG：知识切分 / 向量索引 / 混合检索
├── mcp_tools.py    MCP 客户端：Tavily 工具接入
├── tools.py        业务工具：商品检索 / 详情 / 订单
├── runtime.py      请求级上下文（contextvar）
├── agent.py        编排层：预处理 → 图执行 → SSE 产出
├── main.py         FastAPI 入口
└── schemas.py      数据契约（与 docs/api-contract.md 对齐）
knowledge/
└── guide.md        导购知识库（RAG 语料）
```

## 三、七个技术点的落点

### 1. 会话记忆

分两层，都在 `memory.py`：

| 层 | 存储 | 内容 | 作用 |
|---|---|---|---|
| 短期记忆 | Redis List，TTL 7 天 | 最近 N 轮原文 | 记得上句话说了什么 |
| 长期摘要 | Redis String | 压缩后的需求要点 | 记得用户大概想要什么 |

超过 `MEMORY_MAX_TURNS`（默认 12）条时触发 `_maybe_summarize`：把早期对话交给模型压成 120 字摘要，只保留核心需求、预算、已排除选项，然后 `ltrim` 丢掉原文。

**为什么 Java 也存 history，这里还要存**：Java 存的是"给用户看的聊天记录"（业务数据），这里存的是"给模型看的上下文"（可压缩、可过期的运行态）。两者形态和生命周期都不同。

**降级**：Redis 连不上自动用进程内存，日志警告。本地开发不该被中间件卡住。

### 2. RAG

`rag.py` + `knowledge/guide.md`。

**为什么导购需要 RAG**：商品向量检索（Java 侧 MySQL 向量列 + 应用层算余弦）解决"找出符合条件的具体商品"，但用户问「第一次露营买什么」「300 送女生会不会太便宜」时，要的不是商品而是**导购常识**。这部分在文档里。

所以本项目是**双 RAG**：
- 商品检索 → 工具 `search_products`（走 Java，MySQL 向量列）
- 知识检索 → `rag.py`（进程内向量库）

**切分策略**：先按 Markdown 标题切（标题是天然语义边界），超长段落再按句号二次切分。定长切分会把"预算怎么分配"和"常被忽略的点"切到一起，检索出来答非所问。

**检索策略**：`0.7 × 余弦相似度 + 0.3 × 关键词 IDF 加权`。纯向量在中文电商场景会漏精确词——用户问"IPX5"，向量可能返回"防水性能好"的段落，但用户要的是 IPX5 那一条。所以关键词命中要额外加分。

**降级**：embedding 接口不可用时用字符 n-gram 哈希向量兜底，功能不中断。

实测效果（15 个 chunk）：

| 查询 | 命中段落 | 分数 |
|---|---|---|
| 第一次露营要买什么 | 先买什么，后买什么 | 0.667 |
| 七天无理由退换规则 | 退换 | 0.716 |
| 通勤降噪耳机怎么选 | 按场景分，不是按参数分 | 0.561 |

### 3. 中间件

`middleware.py`，三个，都挂在 `create_agent(middleware=[...])` 上：

**ContextTrimMiddleware** —— 上下文工程。按条数 + 字符数双预算从尾部向前累加裁剪。两条不变量必须守住：
- 首条 system 消息必须保留（丢了提示词，Agent 就失忆）
- `tool_calls` 与 `tool_result` 必须同生同死（只删一半会让上游返回 400）

**ToolRetryMiddleware** —— 工具失败重试一次，仍失败则构造 `ToolMessage` 把错误喂回模型，让它自己决定降级方案。理由：MCP 检索、Java 接口都会瞬时抖动，整条流 500 对用户体验的伤害远大于让模型说一句"这部分数据暂时取不到"。

**AuditMiddleware** —— 记录每次工具调用的耗时和结果规模，落结构化日志。

**为什么用中间件而不是在工具里 try/except**：工具内部捕获会丢堆栈上下文，且每个工具都要写一遍。中间件在协议层统一处理，工具保持纯粹。

### 4. 系统提示词

`prompts.py`，拆成「静态常量 + 动态拼装函数」。

静态部分是四段硬约束：角色、边界（不编造商品/价格/库存）、工具使用策略、输出规范。

动态部分每轮现拼：用户画像、记忆摘要、RAG 知识、已收集槽位、图片描述。

**只在有内容时才拼**——提示词里出现一堆"无"占位符会让模型把注意力浪费在空字段上。

几个具体的约束值得说：
- 「推荐商品必须给理由，且理由要落到具体场景」——这是本项目区别于普通商品列表的核心
- 「一次最多推 3 款」——推太多等于没推
- 「追问时一次只问一个最关键的问题」——不要一口气问三个

### 5. 多模态

`agent.py::describe_image`。

**为什么先转文本再进主流程**：主模型 `qwen-plus` 不吃图片；而且把图片塞进 ReAct 循环会导致每轮工具调用都重复传图（贵且慢）。先调 `qwen3-vl-plus` 转成一次性文本描述，后续所有轮次复用同一段文本，成本降一个数量级。

识图结果会通过 `tool_result` 事件（`tool: "vision"`）推给前端，让用户看到"我理解了什么"。

### 6. State 动态数据

`AgentState`（TypedDict）：

```python
class AgentState(TypedDict, total=False):
    messages: Annotated[list[BaseMessage], add_messages]
    slots: dict[str, str | None]     # 槽位状态
    knowledge: str                    # 本轮命中的知识
    image_desc: str                   # 图片描述
```

`messages` 用 `add_messages` reducer 做增量合并，其余字段是普通覆盖。

**为什么重要**：state 不只有消息，还带本轮算出来的业务数据。这是"上下文工程"的落点——模型看到什么由 state 决定，而不是由调用方随手拼字符串。

### 7. MCP 接入

`mcp_tools.py`。

Tavily 官方 MCP server 通过 stdio 提供 5 个工具，这里**只暴露 2 个**：`tavily_search`、`tavily_extract`。`crawl` / `map` / `research` 对导购场景是过度设计，全塞给模型只会增加误调用概率。

三个工程细节：

**Windows 启动差异**：`npx` 实际是 `npx.cmd`，`CreateProcess` 不能直接拉起 `.cmd`，必须 `cmd /c npx ...`。

**JSON Schema → pydantic**：MCP 工具的 `input_schema` 要转成 pydantic 模型才能给 `StructuredTool` 用。只处理基础类型，嵌套对象当 dict 透传。

**描述改写**：MCP 原始描述是通用的"搜索网页"，模型会拿它当万能工具。这里收紧成导购场景的边界。

**降级**：MCP server 拉不起来（没装 node、没网、key 失效）时只记日志，Agent 继续跑，只是少一个联网工具。

## 四、一次请求的完整流程

```
POST /agent/chat
  │
  ├─ 1. 多模态预处理    有图 → qwen3-vl-plus 识图 → 文本描述
  ├─ 2. 取用户画像      Java /internal/users/{id}/profile（失败不阻断）
  ├─ 3. RAG 检索        query = 用户问题 + 图片描述 → top3 知识片段
  ├─ 4. 取记忆摘要      Redis
  ├─ 5. 拼系统提示词    静态规则 + 画像 + 摘要 + 知识 + 槽位 + 图片描述
  ├─ 6. 载入历史        Java history + Redis 记忆，去重合并
  │
  ├─ 7. LangGraph ReAct 循环
  │     model ──(要调工具?)──▶ tools ──▶ model ──▶ ...
  │       ↑ 穿过 ContextTrim / ToolRetry / Audit 三个中间件
  │
  ├─ 8. 流式产出 SSE     token / tool_call / tool_result / product_card
  └─ 9. 回写记忆         纯文本入 Redis，超阈值触发摘要压缩
```

## 五、SSE 事件与契约的对应

契约里定义 8 种事件，全部实现，且语义一致。新增两个字段（不破坏契约）：

| 事件 | 说明 |
|---|---|
| `meta` | 增加 `traceId` 便于全链路追踪 |
| `tool_result` | 增加 `preview` 字段，前端可选展示；`tool: "vision"` 表示识图结果 |
| `done` | 增加 `toolCalls` 计数 |

## 六、关键取舍

**为什么用 `create_agent` 而不是手写 StateGraph**
ReAct 循环（模型判断要不要调工具 → 调完喂回 → 再判断）是标准模式，`create_agent` 已处理好并行工具调用、tool_call 配对等边界情况。业务逻辑通过 middleware + state_schema 注入，不必重造轮子。

**为什么知识库不做持久化**
知识是静态的、几十 KB、随代码发布。商品向量那边是因为要跟着商品表增删才需要落库，
知识库没有这个问题。进程内 numpy 检索在千级 chunk 规模是微秒级，启动时算一次就够了。
真到需要持久化的量级再迁移，`KnowledgeBase.asearch` 的接口不用改。

**为什么 MCP 用短连接**
长连接更省，但 MCP 子进程在 Windows 上容易因父进程重启变僵尸，且 Tavily 调用频率低（一轮最多一两次），短连接的稳定性收益更大。

**为什么工具轨迹不入记忆**
只存用户消息和助手回答的纯文本。工具返回的 JSON 会污染后续上下文且极耗 token——模型不需要记得上次检索的原始数据，只需要记得结论。

## 七、运行

```bash
cd pilot-agent
cp .env.example .env      # 填 LLM_API_KEY / TAVILY_API_KEY / REDIS_URL

# 启动服务（会自动预热 RAG 索引和 MCP 工具）
.venv/Scripts/python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8000

# 健康检查：能看到 RAG / MCP / 记忆三块的真实状态
curl http://127.0.0.1:8000/agent/health

# 端到端自检（21 项，含多模态与 MCP）
.venv/Scripts/python.exe scripts/e2e_check.py --with-mcp
```

## 八、已知限制

- **没有接入 LangSmith**。当前只有结构化日志（`AuditMiddleware` 记录工具名/耗时/结果规模），
  缺少跨请求的链路追踪与 token 成本统计。
- **LangGraph 只用 `create_agent`，未手写 StateGraph**。ReAct 循环是标准模式，
  预置实现已处理好并行工具调用与 tool_call 配对等边界。代价是没用上条件边与
  `interrupt`（写操作的人工确认目前靠前端二次点击，不是 LangGraph HITL）。
- **MCP 每次调用拉起一个 npx 子进程**，首次约 1.5 秒。若后续调用频繁，应改为长连接 + 健康检查。
- **RAG 无增量更新**。改 `knowledge/guide.md` 需重启服务重建索引。
