# ShopPilot 项目手册

> 本文档由完整的需求讨论过程整理而成，包含项目定位、架构决策、技术选型依据、已实现代码说明、运行方式与踩坑记录。
> 用途：作为项目开发的唯一上下文来源，任何时候接手都能从这里恢复完整认知。

**文档版本**：2026-11-14
**项目路径**：`D:\Git\ShopPilot`
**远程仓库**：https://github.com/Glod-sweet/ShopPilot

---

## 一、项目定位

### 1.1 一句话描述

ShopPilot 是一个基于 Agent 的智能导购系统，让用户用自然语言完成「说需求 → 得到推荐 → 直接下单」的完整购物流程。

### 1.2 要解决的问题

传统电商是「人找货」：用户必须自己想清楚要什么、自己拆关键词、自己比对参数。

但真实购物场景里，用户往往只有一个模糊意图——「想给女朋友买个生日礼物，300 以内」——却不知道对应什么商品、该搜什么词。

ShopPilot 把这段「翻译 + 决策」的工作交给 Agent：用户只表达意图，Agent 负责理解、筛选、对比、解释理由，并在用户确认后代办交易动作。

### 1.3 与已有项目的关系（重要）

用户已有一个电商平台项目，本项目是它的**纵向深化**，不冲突：

> 「第一个项目是传统电商平台，解决的是**交易和履约**问题；第二个项目是在它的商品和订单数据之上，做了一层 **AI 导购前台**，解决的是**用户不知道怎么挑**的问题。两者共享数据层，但技术栈完全不同——前者是典型的 CRUD + 事务，后者是检索 + 推理 + 编排。」

**明确不做**（复用已有电商项目）：商品管理后台、支付渠道对接、物流履约。

**只聚焦**：对话式导购这一层。

### 1.4 场景选择过程（为什么是电商）

讨论中曾评估过多个方向，判断标准是：**「这个业务里，有没有一个需要查数据库、要按规则判断、还要走审批流程的动作？」**

| 候选方向 | 评价 |
|---|---|
| 财务报销 / 费控 | 结构同构、业务不同，曾是首选替代方案 |
| 医院导诊 | 新颖度高，但医疗准确性风险大 |
| 合同审查 | 技术最深，但效果难保证 |
| **电商导购** | **最终选定**：用户已有电商数据可直接复用，价值直观，效果可量化 |

---

## 二、总体架构

### 2.1 三层结构

```
Vue3 (5173) ──HTTP/SSE──▶ Java (8080) ──HTTP/SSE──▶ Python Agent (8000)
                              ▲                            │
                              └────── 工具反向调用 ──────────┘
```

### 2.2 一次请求的完整生命周期

1. **发起**：用户在 Vue 点击发送，前端用 `fetch` 发起 `POST /api/chat/stream`，带 `Authorization: Bearer <JWT>`
2. **准备上下文**：Java 校验 JWT → 查会话 → 读最近 N 条历史 → 组装请求发给 Python
3. **Agent 编排**：Python 判断要不要调工具、要不要检索，开始调大模型产出 token
4. **工具回调**：Agent 需要业务数据时，通过 HTTP **反向调用 Java 的内部接口**
5. **流式回传**：Python 每产出一个 token 就 SSE 推给 Java，Java 用 `Flux` **原样透传**给前端
6. **收尾落库**：流结束后 Java 把完整回答写入消息表，更新 token 用量

### 2.3 三大分工（核心约束）

| 子项目 | 一句话职责 | 拥有什么 | 绝不做什么 |
|---|---|---|---|
| **pilot-web** | 把 Agent 的思考过程展示出来 | 界面状态、SSE 解析、卡片渲染 | 不直连 Python，不存业务数据 |
| **pilot-server** | 唯一入口 + 唯一有状态层 | JWT、会话、商品订单、事务、写权限 | 不写 prompt，不调 LLM |
| **pilot-agent** | 只做推理和决策 | 意图识别、检索编排、工具选择 | 不连数据库，不校验用户身份 |

**核心分界线**：
- **数据归 Java，判断归 Python，呈现归 Vue**
- 写操作（加购、下单）永远由 Java 执行，Python 只能提出建议
- Python 设计成纯函数式无状态服务：传入 `{user_id, session_id, message, history, slots}`，返回流式 token，可随意重启扩容
- 数据库权限不下放给 Python，工具调用走 Java 接口

---

## 三、技术选型

### 3.1 版本矩阵（已固定）

| 层 | 组件 | 版本 | 说明 |
|---|---|---|---|
| 前端 | Node | ≥ 20.19，推荐 22 LTS | 本机 22.22.2 ✅ |
| 前端 | Vue | 3.5.x | |
| 前端 | Vite | 6.x / 7.x | |
| 前端 | TypeScript | 5.7.x | 开了 strict |
| 后端 | JDK | **21 LTS** | 见下方风险提示 |
| 后端 | Spring Boot | **3.5.6** | WebFlux |
| 后端 | Maven | 3.9.x | 本机 3.9.16 ✅ |
| Agent | Python | 3.11 ~ 3.13 | 本机 3.13.14 ✅ |
| Agent | LangChain | 1.x | 未安装，待接 |
| Agent | LangGraph | 1.x | 未安装，待接 |
| Agent | FastAPI | 0.115+ | ✅ |
| 数据 | MySQL | 8.4 | Docker |
| 数据 | PostgreSQL + pgvector | 17 + 0.7+ | Docker |
| 数据 | Redis | 7.x | 可选 |

### 3.2 ⚠️ Java 版本风险（必须处理）

**本机现状**：`JAVA_HOME=D:\jdk-26.0.1`，Java 26.0.1

**问题**：
- Spring Boot 3.x 官方支持到 Java 21~23，**不支持 26**
- Spring Boot 4.0 官方支持 Java 21~25，26 属于「超纲」
- 当前 `pom.xml` 已设置 `<java.version>21</java.version>` 强制编译目标，所以**编译产物是 21 字节码**，能跑
- 但运行时用的是 JDK 26，启动时会出现 `sun.misc.Unsafe` 弃用警告（Netty 相关），属于「不保修」状态

**建议**：装一个 JDK 21 LTS，用 IDEA 按项目指定 JDK，不影响其他项目。这是目前唯一未处理的环境风险。

### 3.3 关键选型理由

| 决策点 | 选择 | 理由 |
|---|---|---|
| Vue↔Java 通信 | **SSE**（非 WebSocket） | 单向流够用，断线重连简单，Nginx 配置友好 |
| Java Web 模型 | **WebFlux**（非 Spring MVC） | 同步阻塞接流会占满线程池，10 个并发就崩 |
| Java↔Python | **REST + SSE**（非 gRPC） | 简单好调试；gRPC 收益要到每秒几百次内部调用才体现 |
| 编排框架 | 先 LangChain，L4 需求出现时上 LangGraph | LangGraph 是有状态图，多步规划/人工确认场景可控性远高于 AgentExecutor |
| 向量库 | **pgvector**（非 Milvus） | 零新增组件，百万级以下文档完全够用 |
| 模型 | DeepSeek / 通义（兼容 OpenAI 协议） | 成本比国外低一个数量级，function calling 成熟；设计成配置化可切换 |
| 前端流式 | 手写 fetch + ReadableStream | `EventSource` 不支持 POST，也无法带 Authorization 头 |
| UI 库 | 暂未引入，原生 CSS | 减少早期依赖，后续可换 Naive UI |

### 3.4 Agent 能力分级（目标 L3，架构预留 L4）

| 级别 | 能力 | 建议 |
|---|---|---|
| L1 | 纯对话（多轮记忆） | 必经之路 |
| L2 | + 工具调用 | 有真实业务价值的第一步 |
| **L3** | **+ RAG / 检索增强** | **本项目目标** |
| L4 | + 任务规划（状态机、人工审批） | 架构预留，接口留 `tool_call`/`state` 字段 |
| L5 | 多 Agent 协作 | 用不上，属于简历驱动开发 |

---

## 四、业务设计

### 4.1 主链路

```
一句话需求 → 意图与槽位 → 混合检索 → 推荐与澄清 → Java 侧完成交易
```

| 阶段 | 内容 |
|---|---|
| 一句话需求 | 语义化表达，如「想给女朋友买个生日礼物，300以内」 |
| 意图与槽位 | 抽取品类、预算、场景 |
| 混合检索 | 向量召回 + 结构化过滤 + 重排序 |
| 推荐与澄清 | 商品卡片 + 推荐理由 + 追问缺失信息 |
| 交易闭环 | Java 侧库存校验、加购、下单 |

### 4.2 三个核心场景

| 场景 | 用户输入 | Agent 要做什么 | 难点 |
|---|---|---|---|
| **场景化选品** | 「想给女朋友买个生日礼物，300 以内」 | 从抽象意图映射到具体品类（香薰、首饰、口红），按预算筛选、按评价排序 | 意图到商品的语义映射，纯关键词搜索做不到 |
| **多品对比决策** | 「这两款耳机哪个更适合跑步」 | 拉取两个 SKU 参数，结合「跑步」场景做取舍（防水、重量、佩戴稳固度） | 参数理解与场景化推理，不能只罗列参数表 |
| **交易代办** | 「上次买的洗衣液再来一单」 | 查历史订单 → 找到商品 → 确认规格 → 加购 | 多工具串联，处理「已下架」「涨价」异常 |

**第三个场景最关键**，它证明 Agent 不只是「会聊天」，而是「能办事」。

### 4.3 混合检索（技术核心）

电商和通用知识库最大的区别：**不能只靠向量相似度**。用户说「500 以内的跑鞋」，纯向量检索会召回 4999 元的限量款和已售罄型号。

必须两段式：

```
用户需求 → 结构化抽取（品类/价格区间/品牌/属性）
        → 向量召回 top 50（语义相关）
        → 结构化过滤（价格、库存、上架状态）
        → 重排序（销量、评分、毛利）
        → 返回 top 5 给 LLM 生成推荐理由
```

**这是项目最有技术含量的一段**，也是和「随便接个 RAG」拉开差距的地方。

数据库设计（已实现，见 `pilot-infra/sql/pg/01_init_vector.sql`）：

```sql
CREATE TABLE product_embedding (
  product_id   BIGINT PRIMARY KEY,
  title        TEXT,
  content      TEXT,              -- 标题+卖点+属性拼接后向量化
  embedding    vector(1024),
  category_id  BIGINT,            -- 冗余，用于过滤
  price        NUMERIC(10,2),     -- 冗余，用于范围过滤
  brand_id     BIGINT,
  on_shelf     BOOLEAN,
  stock        INT,
  updated_at   TIMESTAMPTZ
);
CREATE INDEX ON product_embedding USING hnsw (embedding vector_cosine_ops);
```

`category_id` 和 `price` 冗余进来，是为了让过滤和检索在一次查询里完成，避免先向量召回再回表过滤导致召回数量不够。用 HNSW 而非 IVFFlat，因为更适合增量写入。

### 4.4 槽位状态机（多轮对话关键）

用户第一句话往往信息不全，不能让 LLM 自由发挥。

| 槽位 | 是否必填 | 缺失时的追问 |
|---|---|---|
| 品类 category | **必填** | 「您想买哪类商品呢？」 |
| 预算 budget | **必填** | 「大概预算多少？我好帮您筛选」 |
| 使用场景 scene | 选填 | 不追问，但影响排序 |
| 品牌偏好 | 选填 | 不追问 |

**规则**：必填槽位齐了才执行检索，否则只追问。避免「用户说买礼物，Agent 直接返回一堆乱七八糟的商品」。

实现上用 LangGraph 的 state 管理，每个节点检查 `missing_slots`，非空走追问分支。当前骨架版本在 `agent.py` 里用简单函数实现，逻辑等价。

### 4.5 效果评估（最大加分项）

电商项目的好处是**效果可量化**，这是通用问答类项目做不到的。

埋点表设计：

```sql
CREATE TABLE agent_recommend_log (
  id BIGINT PRIMARY KEY,
  session_id BIGINT,
  query TEXT,                  -- 用户原始输入
  recalled_ids JSON,           -- 召回了哪些商品
  shown_ids JSON,              -- 实际展示的
  clicked_ids JSON,            -- 用户点了哪些
  ordered_ids JSON,            -- 最终下单的
  latency_ms INT,
  created_at TIMESTAMP
);
```

能算出：**推荐点击率、加购转化率、平均响应时延**。汇报时甩这三个数字，比说一百句「体验很好」都有力。

---

## 五、接口契约（摘要）

> 完整版本见 `docs/api-contract.md`，**这是三端唯一的事实来源**，任何字段变更必须先改它。

### 5.1 服务与端口

| 服务 | 端口 | 对外 |
|---|---|---|
| pilot-web | 5173 | 是 |
| pilot-server | 8080 | **是**（唯一入口） |
| pilot-agent | 8000 | 否（内网） |
| MySQL | 3306 | 否 |
| PostgreSQL | 5432 | 否 |
| Redis | 6379 | 否 |

### 5.2 鉴权

- 前端 → Java：`Authorization: Bearer <JWT>`
- Java → Python：`X-Internal-Token` + `X-User-Id`
- Python → Java（工具调用）：同上

Python **不解析 JWT**，用户身份完全由 Java 通过 `X-User-Id` 传入。

### 5.3 SSE 事件类型

| type | 触发时机 | 前端处理 |
|---|---|---|
| `meta` | 流开始 | 记录 sessionId、messageId |
| `token` | LLM 逐字输出 | 追加到气泡文本 |
| `tool_call` | Agent 决定调工具 | 显示「正在检索商品…」 |
| `tool_result` | 工具返回 | 收起状态 |
| `product_card` | 召回商品 | 渲染商品卡片列表 |
| `slot_ask` | 槽位缺失需追问 | 渲染快捷选项按钮 |
| `done` | 流正常结束 | 结束 loading，写历史 |
| `error` | 出错 | 显示错误提示 |

### 5.4 错误码

| code | 含义 | 可重试 |
|---|---|---|
| `UNAUTHORIZED` | token 无效 | 否 |
| `RATE_LIMITED` | 触发限流 | 是 |
| `AGENT_TIMEOUT` | Python 超时 | 是 |
| `AGENT_ERROR` | Python 内部异常 | 是 |
| `UPSTREAM_ERROR` | LLM 上游异常 | 是 |

### 5.5 超时链（必须逐层递增）

| 链路 | 超时 |
|---|---|
| 前端 fetch | 60s |
| Java → Python | 90s |
| Python → LLM | 120s |
| Python → Java 工具 | 5s |

否则会出现「回答到一半断掉」。

### 5.6 写操作确认机制

`add_to_cart` 等写操作**不允许 Agent 直接执行**：

1. Agent 返回 `tool_call`，`args` 带 `needConfirm: true`
2. 前端渲染确认按钮
3. 用户点击后，前端调 `POST /api/cart/confirm`（走正常用户鉴权，不经 Agent）
4. 成功后前端告知 Agent 结果

写操作始终在用户明确授权下发生，符合最小权限原则。

### 5.7 命名约定

- JSON 字段：`camelCase`
- 数据库字段：`snake_case`
- SSE event 名：`snake_case`
- 时间：ISO 8601，如 `2026-11-14T17:30:00+08:00`
- 金额：`number`，单位元，两位小数（不用字符串，不用分）

---

## 六、已完成的代码

**提交**：`7080a14`（本地）/ `3511665`（远端），39 个文件，3723 行

### 6.1 目录结构

```
ShopPilot/
├── .gitignore
├── README.md
├── docs/
│   ├── api-contract.md          三端接口契约
│   └── project-handbook.md      本文档
├── pilot-web/                   Vue3 前端
│   ├── index.html
│   ├── package.json
│   ├── tsconfig.json
│   ├── vite.config.ts
│   └── src/
│       ├── main.ts
│       ├── App.vue              对话界面（全部 UI 逻辑）
│       ├── style.css
│       ├── api/chat.ts          SSE 流式客户端
│       └── types/sse.ts         事件类型定义
├── pilot-server/                Java 后端
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/shoppilot/
│       │   ├── PilotServerApplication.java
│       │   ├── chat/
│       │   │   ├── ChatController.java       /api/chat/stream
│       │   │   ├── AgentClient.java          连 Python
│       │   │   ├── HealthController.java
│       │   │   └── dto/
│       │   │       ├── ChatRequest.java
│       │   │       └── AgentChatRequest.java
│       │   ├── config/
│       │   │   ├── AgentClientConfig.java    WebClient + 超时
│       │   │   ├── CorsConfig.java
│       │   │   └── PilotAgentProperties.java
│       │   └── internal/
│       │       └── InternalToolController.java  /internal/* 供 Python 调用
│       └── resources/application.yml
├── pilot-agent/                 Python Agent
│   ├── pyproject.toml
│   ├── .env.example
│   ├── .python-version          3.13
│   ├── run.py
│   ├── app/
│   │   ├── __init__.py
│   │   ├── main.py              FastAPI 入口
│   │   ├── agent.py             编排逻辑（当前 mock 流式）
│   │   ├── schemas.py           数据契约
│   │   ├── java_client.py       工具数据来源
│   │   └── config.py            配置
│   └── scripts/e2e_check.py     端到端验证脚本
└── pilot-infra/
    ├── docker-compose.yml       MySQL + PG/pgvector + Redis
    ├── .env.example
    └── sql/pg/01_init_vector.sql
```

### 6.2 各文件职责要点

**pilot-web**

| 文件 | 要点 |
|---|---|
| `src/api/chat.ts` | 手写 SSE 帧解析（`\n\n` 分帧，兼容 `\r\n`），半截 JSON 直接丢弃不打断流 |
| `src/App.vue` | 槽位在前端累积（`slots` ref），`pendingSlot` 记录当前等待哪个槽位 |
| `src/types/sse.ts` | 与 `api-contract.md` 严格对应 |

**pilot-server**

| 文件 | 要点 |
|---|---|
| `ChatController` | 返回 `Flux<ServerSentEvent>`，**绝不 collectList()**，否则流式退化成「憋完整再吐」；响应头带 `X-Accel-Buffering: no` |
| `AgentClient` | WebClient 透传，`doOnError` 只记日志不改内容 |
| `InternalToolController` | 当前 mock 数据，真实实现需接商品/订单库 |
| `PilotAgentProperties` | record + `@ConfigurationPropertiesScan`，非 Lombok |

**pilot-agent**

| 文件 | 要点 |
|---|---|
| `agent.py` | 槽位检查**前置**：缺信息只追问、不检索；工具失败转 `error` 事件不编造数据 |
| `main.py` | 只做协议转换；校验 `X-Internal-Token`；身份以 `X-User-Id` 为准，不信任请求体里的 userId |
| `java_client.py` | 工具数据唯一来源，硬约束不连库；超时 5s 快速失败 |

### 6.3 端到端验证结果（真实跑通）

```
→ POST /api/chat/stream
  status=200
  X-Session-Id=s_bab31cc26ba04965
  Content-Type=text/event-stream;charset=UTF-8
  [product_card] {"products": [{"productId": 100234, "title": "木质调香薰礼盒", ...}]}
  [done] {"messageId": "m_4993227", "finishReason": "stop", "totalTokens": 106, "latencyMs": 1875}

=== 结果 ===
  事件序列: ['meta', 'tool_call', 'tool_result'] … ['token', 'done']
  事件总数: 111
  token 数: 106
  首 token 延迟: 0.70s
  总耗时: 2.36s
  拼接文本: 根据您的需求「想给女朋友买个生日礼物，300以内」，我从商品库里筛选出几款符合预算的选择…

PASS: 三端流式链路正常
```

---

## 七、如何运行

### 7.1 完整启动顺序

**① Python Agent（先起，Java 会连它）**

```bash
cd pilot-agent
cp .env.example .env          # 按需填 LLM_API_KEY
uv venv                       # 创建虚拟环境
uv pip install -e .           # 装全依赖（含 LangChain/LangGraph）
.venv/Scripts/python.exe run.py
```

**② Java 后端**

```bash
cd pilot-server
SERVER__PORT=8080 mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Dserver.port=8080"
```

> ⚠️ **必须加 `SERVER__PORT=8080`**，原因见第八章踩坑记录 #1。

**③ 前端**

```bash
cd pilot-web
npm install
npm run dev
```

打开 http://localhost:5173

**④ 基础设施（做向量检索时才需要）**

```bash
cd pilot-infra
cp .env.example .env
docker compose up -d
```

### 7.2 验证链路

```bash
cd pilot-agent
.venv/Scripts/python.exe scripts/e2e_check.py
```

期望输出 `PASS: 三端流式链路正常`。

### 7.3 单独测试各端

```bash
curl http://localhost:8080/api/health
curl http://localhost:8000/agent/health
```

---

## 八、踩坑记录

### #1 环境变量覆盖 Spring Boot 端口

**现象**：`mvn spring-boot:run` 报 `Port 58151 was already in use`

**原因**：本机环境存在 `SERVER__PORT=58151`，Spring Boot 的宽松绑定会把 `SERVER__PORT` 解析成 `server.port`，优先级高于 `application.yml`

**解决**：启动时显式覆盖 `SERVER__PORT=8080`

**影响**：所有本地启动命令都要带这个前缀

### #2 EventSource 不能用于本项目

**现象**：想用原生 `EventSource` 收流，发现无法发 POST，也无法带 Authorization 头

**原因**：`EventSource` API 只支持 GET，且不支持自定义请求头

**解决**：改用 `fetch` + `ReadableStream` 手动解析 SSE 帧

### #3 ChatRequest 缺 slots 字段

**现象**：端到端测试走的是追问分支，永远拿不到商品

**原因**：初版 `ChatRequest` 只有 `sessionId`/`message`，前端点了槽位选项后无法回传已收集的槽位，Python 每次都认为槽位为空

**解决**：`ChatRequest` 增加 `Slots slots` 字段，前端在 `slots` ref 里累积后随请求回传

### #4 git amend 导致远端分叉

**现象**：push 报 `[rejected] (non-fast-forward)`

**原因**：先用英文提交信息提交并推送（`3511665`），随后在 IDEA 里勾选「修改提交」amend 成中文信息（`7080a14`）。amend 会创建全新提交替换旧的，hash 变化，但远端还留着旧提交，两条线分叉

**当前状态**：`git diff 3511665 7080a14` 结果为空，**两个提交文件内容完全一致**，只差提交信息

**尚未解决**，方案见第九章

**教训**：不要随意 amend 已推送的提交；如果改了，要立刻 `--force-with-lease`

### #5 网络访问 GitHub 不稳定

**现象**：三种不同报错交替出现

| 报错 | 含义 |
|---|---|
| `Recv failure: Connection was reset` | 连接被中断（代理/防火墙干扰） |
| `Failed to connect to github.com port 443 after 21075 ms` | 直接超时 |
| `[rejected] (non-fast-forward)` | **连上了**，服务器正常拒绝 |

**诊断**：环境变量配了 `http_proxy=http://127.0.0.1:58178`，但 git 自身未配置代理（`git config --get-regexp proxy` 为空）。从当前环境访问 `github.com` 8 秒超时

**注意**：这是**两个独立问题**。网络修好了，分叉还在，push 照样被拒

---

## 九、待处理事项

### 9.1 阻塞项：git 分叉

**现状**

```
* 7080a14  初步框架-三国鼎立          ← 本地 main
| * 3511665  feat: 三端骨架与...      ← 远端 main
|/
* 68e0e14  Initial commit
```

`git rev-list --left-right --count origin/main...main` → `1  1`（各领先一个）

**三个方案**

| 方案 | 操作 | 结果 | 风险 |
|---|---|---|---|
| **A. 放弃本地提交信息** | `git reset --hard origin/main` | 本地变成 `3511665`，英文信息 | 丢「三国鼎立」这个中文信息，代码不丢 |
| **B. 强制覆盖远端（推荐）** | `git push --force-with-lease` | 远端变成 `7080a14`，保留中文信息 | 内容相同，实际无风险；`--force-with-lease` 比 `--force` 安全 |
| C. 合并两边 | `git pull --rebase` | 产生多余合并提交 | 历史变脏，不推荐 |

**推荐 B**，理由：内容完全一致，仓库单人使用，`--force-with-lease` 在远端被别人改过时会拒绝执行，不会盲覆盖。

**处理顺序**：先修网络（让 git 走代理），再执行方案。

### 9.2 环境项

- [ ] **装 JDK 21 LTS**（当前 JDK 26 属于「不保修」状态）
- [ ] IDEA 里右键 `pilot-server/pom.xml` → **Add as Maven Project**（否则不索引依赖）
- [ ] `pilot-agent` 装全依赖：`uv pip install -e .`（LangChain / LangGraph 还没装）
- [ ] 装 Docker（或先用已有电商项目的 MySQL，P3 之后再上 pgvector）

### 9.3 开发项

- [ ] **接真实 LLM**：替换 `pilot-agent/app/agent.py` 里 `for ch in reply` 循环，改用 LangChain `astream`，事件结构不变
- [ ] 把 `run_agent` 换成 LangGraph 状态机，正式管理槽位
- [ ] `InternalToolController` 接真实商品/订单数据
- [ ] 实现混合检索（向量 + 结构化过滤 + 重排序）
- [ ] 加 JWT 鉴权（当前 `ChatController` 硬编码 `userId = 1L`）
- [ ] 会话持久化（消息表、会话表）
- [ ] 埋点表与效果统计

---

## 十、分阶段路线

| 阶段 | 交付 | 验收标准 |
|---|---|---|
| **P1** | 单轮对话 + 纯向量检索 + 商品卡片 | 说「300 以内的蓝牙耳机」能出真实商品卡片 |
| **P2** | 加结构化过滤 + 槽位追问 | 「买礼物」会反问预算；「500 以内」不推 5000 的 |
| **P3** | 加交易工具（加购/再来一单） | 说「再来一单」能真的进购物车 |
| **P4** | 加多品对比 + 埋点评估 | 能对比两款；后台能看到点击率 |
| **P5** | 流式 + 会话持久化 + 前端打磨 | 逐字输出，历史会话可回看 |

**P1 和 P2 是分水岭**。P1 只证明「能连上模型」，P2 才证明「懂电商」。建议 P1 快速做（两三天），精力压在 P2 的混合检索上。

**当前进度**：骨架层面已完成（P1 的链路部分），业务逻辑尚未接真实数据。

---

## 十一、关键原则速查

写代码时随时对照这几条：

1. **Java 是唯一有状态层** — 鉴权、会话、账单、审计全在 Java；Python 无状态，可随意重启扩容
2. **数据只在边界处序列化** — 三端之间传 JSON，不共享对象模型，字段名以 `api-contract.md` 为准
3. **流式是「透传」不是「转发」** — Java 收到 Python 的流立刻原样推给前端，绝不攒起来
4. **工具调用是 Python 反向找 Java** — 不是 Python 直接连数据库，写权限只属于 Java
5. **超时必须逐层递增** — 前端 60s < Java 90s < Python 120s
6. **写操作需用户二次确认** — Agent 只能建议，不能直接执行
7. **Nginx 必须关缓冲** — `proxy_buffering off;` + `X-Accel-Buffering: no`，否则流式会变成憋 10 秒一次性吐
8. **Python 生产用常驻容器** — LangChain 加载嵌入模型要几秒，别用 Serverless

---

## 十二、Java 商城框架（2026-11-14 新增）

在原有「对话链路骨架」之上，补齐了完整的商城业务骨架。**现在 Java 侧不只是一个 SSE 透传管道，而是一个可独立运行、可独立验收的电商后端。**

### 12.1 分层结构

```
com.shoppilot
├── common/         统一响应 ApiResponse、错误码 ErrorCode、异常处理、traceId、RowValues
├── security/       JWT 签发校验、鉴权过滤器、内部接口过滤器、BCrypt
├── config/         R2DBC 数据源、Agent WebClient、CORS
├── user/           注册 / 登录 / 当前用户
├── catalog/        商品列表、详情、品类树、品牌、场景（人找货）
├── search/         混合检索（货找人，Agent 专用）
├── trade/          购物车、订单、下单事务
├── chat/           会话、SSE 透传、消息落库
├── internal/       供 Python 反向调用的工具接口
├── observability/  推荐埋点、工具调用日志、效果统计
└── repository/     R2DBC 数据访问（DatabaseClient + Map 行）
```

**为什么用 `DatabaseClient` + `Map<String,Object>` 而不是 JPA/MyBatis 实体**：
商品表有 `tags`/`attributes`/`album` 多个 JSON 列，`sp_product_embedding` 是纯技术表，
硬套 ORM 会产生大量「查出来再拼装」的胶水代码。直接用 Map 行 + `RowValues` 取值，
配合 `record` 作为出参 DTO，反而更短更清楚。

### 12.2 鉴权设计

不引入 Spring Security 整套过滤器链，只引 `spring-security-crypto` 用 BCrypt：

| 组件 | 职责 |
|---|---|
| `JwtService` | 签发/校验，HS384，subject=userId |
| `AuthWebFilter` | 校验 `Authorization: Bearer`，把 `UserContext` 放进 exchange |
| `InternalTokenFilter` | 校验 `/internal/**` 的 `X-Internal-Token`，定长比较防时序攻击 |
| `PasswordConfig` | 只暴露一个 `PasswordEncoder` Bean |

**信任边界**：`/internal/**` 的用户身份**只认 `X-User-Id` 请求头**，不接受请求体/查询参数里的 userId。
否则 Agent 能伪造身份读别人的订单历史。

### 12.3 下单事务（唯一需要事务的地方）

```
① 校验（下架 / 售罄 / 库存不足，逐条给明确提示）
② 原子扣库存  UPDATE sp_product SET stock = stock - n WHERE id = ? AND stock >= n
③ 写订单主表
④ 写订单明细（标题/规格做快照）
⑤ 清购物车已结算条目
```

**并发控制**：靠 ② 的「带条件 UPDATE」。`rowsUpdated == 0` 即库存不够，抛错整体回滚。
InnoDB 会对该行加排他锁，天然串行化，不需要分布式锁或悲观锁。

**取消订单**：只有 `status=0`（待付款）可取消，用 `WHERE status=0` 条件保证幂等，
取消后按明细回补库存。已实测 42 → 40 → 42 正确恢复。

**金额安全**：下单请求体里**没有金额字段**，一律服务端按当前数据库价格重算。
运费规则（满 99 免运费，否则 8 元）集中在 `OrderService` 一处。

### 12.4 本轮踩的坑（都是真实故障，不是推测）

#### #6 R2DBC 把 TINYINT(1) 读成 Boolean，导致「商品全部下架」

**现象**：商品 `on_shelf=1` 千真万确，加购却返回「商品已下架」。

**根因**：R2DBC MySQL 驱动默认 `tinyInt1isBit=true`，把 `TINYINT(1)` 映射成 `java.lang.Boolean`。
代码写的是 `o instanceof Number n && n.intValue() == 1`，Boolean 走不进这个分支，恒为 false。

**影响面**：`on_shelf` / `selected` / `status` / `is_complete` / `gift_wrappable` 全是 `TINYINT(1)`，
一次性全错——购物车「勾选」失效、订单状态判断失效。

**修复（两层）**：
1. `R2dbcConfig` 显式 `.tinyInt1isBit(false)`，从驱动层统一成数值
2. 新增 `common/RowValues`，Boolean / Number / String 三种形态都能正确取 int，作为兜底

#### #7 `SELECT LAST_INSERT_ID()` 在 R2DBC 下不可靠

**现象**：加购成功但返回的 `cartItemId` 是 `0`。

**根因**：R2DBC 不保证后续语句落在同一连接上，`LAST_INSERT_ID()` 是**连接级**会话变量。

**修复**：插入后按唯一业务键回查——`session_no` / `message_no` / `order_no` / `(user_id,product_id,sku_id)`
都有唯一索引，回查比 `LAST_INSERT_ID()` 稳。已全部替换。

#### #8 `bind(name, null)` 直接抛异常

**现象**：下单报 `Value for parameter spec must not be null. Use bindNull(…) instead.`

**根因**：单规格商品没有 SKU 规格，`sku_spec` 传 null，但代码用了 `bind` 而非 `bindNull`。

**修复**：抽出 `repository/SafeBind`，`bind()` 遇到 null 自动走 `bindNull`。
同时补了 `BigDecimal` / `int` / `long` / `boolean` 重载——缺 `BigDecimal` 重载时金额会落到 `Object` 分支。

### 12.5 已验收的链路

| 场景 | 结果 |
|---|---|
| 登录 → JWT 签发 | ✅ `zhangsan/123456` 拿到 token，7 天有效 |
| 商品分页 / 关键词 / 价格区间 / 排序 | ✅ 32 件商品，筛选排序正确 |
| 品类树（一级含子品类商品） | ✅ 数码配件下 9 件 |
| 商品详情（SKU + 参数 + 场景理由） | ✅ 声浪3 返回 2 个 SKU、5 个参数、3 个场景 |
| 加购 → 购物车视图 | ✅ 金额、勾选、异常提示正确 |
| 下单（扣库存 + 清购物车） | ✅ 300→297、88→87，购物车清空，金额 ¥295.7 精确 |
| 订单状态机 | ✅ 待付款→已付款；重复支付被拒；已付款不可取消 |
| 取消回补库存 | ✅ 42→40→42 |
| 越权访问他人订单 | ✅ 返回 404，不暴露资源存在 |
| 无 token / 伪造 token | ✅ 401 |
| 内部接口无 `X-Internal-Token` | ✅ 401 |
| 会话隔离 | ✅ wangwu 只看到自己的 1 个会话 |

### 12.6 尚未做的（下一步）

- [ ] 混合检索的 embedding 回填：当前 `dim=0`，语义分用「字符二元组 + 关键词覆盖」近似
- [ ] 购物车加购时的 `sessionId` 归因：`s_xxx` → 库内主键的转换还是空实现
- [ ] 订单列表的 N+1：现在逐单查明细，订单多了要改成一次 `IN` 查询
- [ ] 埋点写入用了 `subscribe()` 异步，进程退出时可能丢最后几条
- [ ] Python 侧接真实 LLM，替换 `agent.py` 里的 mock 流

---

## 附：项目命名候选（备查）

最终选定 **ShopPilot（购航）**。其他候选：

| 名字 | 中文 | 寓意 |
|---|---|---|
| ShopPilot | 购航 | Pilot 在 AI 领域是共识词汇，一看就知道有 Agent 参与；强调「领航」而非「客服」 |
| ShopClerk | 智能店员 | Clerk 表示店员，直接点出「会办事」 |
| PickWise | 知选 | 双关「知道该选什么」和「知识驱动」 |
| ShopConcierge | 私人买手 | 高端感，名字偏长 |
| AgentMall | 智购商城 | 直白但缺少个性 |

选择 ShopPilot 的理由：同时说清「这是 Agent」和「这是购物场景」，两个信息一个词占住；技术上也顺——仓库名 `shop-pilot`，模块 `pilot-web`/`pilot-server`/`pilot-agent` 一眼看出三端分工。
