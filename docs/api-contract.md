# ShopPilot 三端接口契约

> 本文件是三端唯一的事实来源。前端、Java、Python 全部照此实现，任何字段变更必须先改本文件。

## 1. 服务与端口

| 服务 | 端口 | 对外 | 说明 |
|---|---|---|---|
| pilot-web | 5173 | 是 | Vite 开发服务器 |
| pilot-server | 8080 | **是** | 唯一对外入口，前端只认它 |
| pilot-agent | 8000 | 否 | 内网访问，不暴露给前端 |
| MySQL | 3306 | 否 | 业务库 + 商品向量（JSON 列，应用层算余弦） |
| Redis | 6379 | 否 | 会话缓存（可选） |

## 2. 鉴权

- 前端 → Java：`Authorization: Bearer <JWT>`
- Java → Python：`X-Internal-Token: <服务间密钥>` + `X-User-Id: <用户ID>`
- Python → Java（反向工具调用）：同上 `X-Internal-Token`

Python **不解析 JWT**，用户身份完全由 Java 通过 `X-User-Id` 传入。

## 3. Vue → Java

### POST /api/chat/stream

发起对话，返回 SSE 流。

**Request**

```json
{
  "sessionId": "s_20261114_001",
  "message": "这两款哪个更值",
  "slots": { "category": null, "budget": null, "scene": null },
  "compareProductIds": [200301, 200401],
  "image": null,
  "clientTraceId": "uuid-v4"
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| sessionId | string | 否 | 为空则新建会话，由响应头 `X-Session-Id` 返回 |
| message | string | 是 | 用户输入，1~2000 字符 |
| slots | object | 否 | 已收集的槽位，前端累积后回传 |
| **compareProductIds** | **number[]** | **否** | **参与对比的商品 ID，1~5 个。非空时 Agent 进入对比模式** |
| image | string | 否 | 多模态图片：base64 / data URL / http 地址 |
| clientTraceId | string | 否 | 前端埋点用，原样回传 |

**对比模式说明**：

用户在前端勾选若干商品后发起提问，`compareProductIds` 随之带上。此时 Agent：

1. 逐件拉取商品详情（`get_product_detail`），拿到参数与库存
2. 按「质量 / 性价比 / 适配场景」三个维度横向比较
3. 给出明确结论（推荐哪一件、为什么、什么情况下选另一件）
4. 不重新执行商品检索 —— 用户已经指定了要比的对象

`compareProductIds` 超过 5 个时，Java 侧截断为前 5 个并记 warn 日志。少于 1 个等同于不传。

**Response**：`Content-Type: text/event-stream`

响应头必须包含：

```
Content-Type: text/event-stream
Cache-Control: no-cache
X-Accel-Buffering: no
X-Session-Id: s_20261114_001
```

## 4. SSE 事件协议

每个事件格式固定为：

```
event: <type>
data: <json>

```

### 4.1 事件类型总表

| type | 触发时机 | 前端处理 |
|---|---|---|
| `meta` | 流开始 | 记录 sessionId、messageId |
| `token` | LLM 逐字输出 | 追加到气泡文本 |
| `tool_call` | Agent 决定调工具 | 显示"正在检索商品…"等状态 |
| `tool_result` | 工具返回 | 收起状态，可选展示 |
| `product_card` | 召回商品 | 渲染商品卡片列表 |
| `slot_ask` | 槽位缺失需追问 | 渲染快捷选项按钮 |
| `done` | 流正常结束 | 结束 loading，写入历史 |
| `error` | 出错 | 显示错误提示 |

### 4.2 各事件 data 结构

**meta**

```json
{ "sessionId": "s_20261114_001", "messageId": "m_8821", "model": "qwen-plus", "traceId": "uuid-v4" }
```

**token**

```json
{ "text": "根据您的预算，" }
```

**tool_call**

```json
{ "tool": "search_products", "displayText": "正在为您筛选商品…", "args": { "category": "gift", "maxPrice": 300 } }
```

`tool` 可能取值：`search_products` / `get_product_detail` / `get_order_history` / `tavily_search` / `tavily_extract`。

**tool_result**

```json
{ "tool": "search_products", "count": 12, "elapsedMs": 340, "preview": "检索到 3 件商品：…" }
```

`preview` 为截断后的结果摘要，前端可选展示。识图结果也走这个事件，`tool` 为 `"vision"`、`preview` 为图片描述。

**product_card**（最关键的结构）

```json
{
  "products": [
    {
      "productId": 100234,
      "title": "木质调香薰礼盒",
      "subTitle": "含香薰蜡烛与扩香石",
      "price": 268.00,
      "originalPrice": 328.00,
      "coverUrl": "https://cdn.example.com/p/100234.jpg",
      "stock": 42,
      "salesCount": 1820,
      "rating": 4.8,
      "tags": ["礼物包装", "顺丰包邮"],
      "reason": "预算内且带礼盒包装，适合送人"
    }
  ]
}
```

`reason` 是 Agent 生成的一句话推荐理由，**必填**，这是本项目区别于普通商品列表的核心。

**slot_ask**

```json
{
  "slot": "budget",
  "question": "大概预算多少呢？我好帮您筛选",
  "options": ["100以内", "100-300", "300-500", "500以上"]
}
```

**done**

```json
{ "messageId": "m_8821", "finishReason": "stop", "totalTokens": 486, "toolCalls": 2, "latencyMs": 2140 }
```

**error**

```json
{ "code": "AGENT_TIMEOUT", "message": "服务响应超时，请重试", "retryable": true }
```

错误码约定（SSE 流内的 `error` 事件用字符串码）：

| code | 含义 | 可重试 |
|---|---|---|
| `UNAUTHORIZED` | token 无效 | 否 |
| `RATE_LIMITED` | 触发限流 | 是 |
| `AGENT_TIMEOUT` | Python 超时 | 是 |
| `AGENT_ERROR` | Python 内部异常 | 是 |
| `UPSTREAM_ERROR` | LLM 上游异常 | 是 |

### 4.3 业务接口错误码（普通 JSON 接口用数字码）

结构固定为 `{ "code": 0, "message": "ok", "data": {}, "traceId": "..." }`。

| code | HTTP | 含义 | 可重试 |
|---|---|---|---|
| 0 | 200 | 成功 | — |
| 1001 | 400 | 参数不合法 | 否 |
| 1002 | 401 | 未登录 / token 失效 | 否 |
| 1003 | 403 | 无权访问 | 否 |
| 1004 | 404 | 资源不存在 | 否 |
| 1005 | 409 | 资源状态冲突 | 否 |
| 1006 | 429 | 请求过于频繁 | 是 |
| 2001 | 409 | 库存不足 | 否 |
| 2002 | 400 | 购物车为空 | 否 |
| 2003 | 409 | 商品已下架 | 否 |
| 2004 | 409 | 订单状态不允许该操作 | 否 |
| 3001 | 504 | Agent 超时 | 是 |
| 3002 | 502 | Agent 异常 | 是 |
| 3003 | 502 | 上游异常 | 是 |
| **4001** | **503** | **数据服务不可用（数据库连不上 / 连接池耗尽）** | **是** |
| 5000 | 500 | 服务器内部错误（未预期异常） | 否 |

**4001 与 5000 的区别（重要）**：

- `4001` 是「依赖挂了」——数据库没启动、连接被中断、连接池耗尽。**重试有意义**，前端应提示"稍后重试"而不是"联系管理员"。
- `5000` 是「代码出错了」——未预期的空指针、类型转换失败等。重试通常无效。

前端不应把 5000 的原始文案直接展示给用户，那对用户毫无信息量；只在开发模式下显示业务码，生产环境统一提示"服务异常，请稍后重试"。

> 越权访问他人资源统一返回 **404**（而非 403），避免暴露资源是否存在。

## 5. Java → Python

### POST /agent/chat

内部接口，返回与第 4 节完全相同的 SSE 事件流。Java 只做**原样透传**，不修改事件内容。

**Request**

```json
{
  "userId": 10086,
  "sessionId": "s_20261114_001",
  "message": "想给女朋友买个生日礼物，300以内",
  "history": [
    { "role": "user", "content": "你好" },
    { "role": "assistant", "content": "您好，想买点什么？" }
  ],
  "slots": { "category": null, "budget": null, "scene": null },
  "compareProductIds": [],
  "traceId": "uuid-v4",
  "image": null
}
```

| 字段 | 说明 |
|---|---|
| history | 由 Java 从数据库读取后传入，**Python 不查库** |
| slots | 上一轮槽位状态，由 Java 持久化，Python 无状态 |
| traceId | 全链路追踪，Python 日志必须打印 |
| image | 可选。base64 / data URL / http 图片地址。非空时走多模态分支，先经 VL 模型识图 |

### GET /agent/health

```json
{
  "status": "ok",
  "version": "0.2.0",
  "model": "qwen-plus",
  "vlModel": "qwen3-vl-plus",
  "embeddingModel": "text-embedding-v2",
  "rag": { "chunks": 15, "degraded": false },
  "mcp": { "ready": true, "tools": ["tavily_search", "tavily_extract"], "error": null },
  "memory": { "backend": "redis", "ok": true }
}
```

`rag.degraded=true` 表示 embedding 接口不可用，已降级为哈希向量（功能可用，效果下降）。
`mcp.ready=false` 时 `error` 给出原因，此时 Agent 只是缺少联网工具，对话仍可用。

### DELETE /agent/session/{sessionId}

清空该会话的 Redis 记忆（原文 + 摘要）。调试与「新建对话」用，需带 `X-Internal-Token`。

## 6. Python → Java（反向工具调用）

Agent 的工具通过以下接口取数据。**数据库权限不下放给 Python。**

| 工具名 | 调用接口 | 说明 |
|---|---|---|
| `search_products` | `POST /internal/products/search` | 向量 + 结构化混合检索 |
| `get_product_detail` | `GET /internal/products/{id}` | 单商品详情与参数 |
| `check_stock` | `GET /internal/inventory/{skuId}` | 实时库存 |
| `get_order_history` | `GET /internal/orders/recent?userId=` | 最近订单，支持"再来一单" |
| `add_to_cart` | `POST /internal/cart/items` | 加购（写操作） |
| `get_user_profile` | `GET /internal/users/{id}/profile` | 偏好、历史消费区间 |

统一响应包装：

```json
{ "code": 0, "message": "ok", "data": { }, "traceId": "uuid-v4" }
```

`code != 0` 时 Python 侧必须把错误转成 `error` 事件，不能让 Agent 编造数据。

## 7. 写操作的确认机制

`add_to_cart` 等写操作**不允许 Agent 直接执行**。流程：

1. Agent 返回 `tool_call`，`args` 中带 `needConfirm: true`
2. 前端渲染确认按钮
3. 用户点击后，前端调 `POST /api/cart/confirm`（走正常用户鉴权，不经过 Agent）
4. 成功后前端告知 Agent 结果

这样写操作始终在用户明确授权下发生，符合最小权限原则。

## 8. 超时与重试

| 链路 | 超时 | 说明 |
|---|---|---|
| 前端 fetch | 60s | 无数据 30s 则提示"仍在处理" |
| Java → Python | 90s | 必须大于前端 |
| Python → LLM | 120s | 必须大于 Java |
| Python → Java 工具 | 5s | 快速失败，不能让工具拖死整条流 |

**逐层递增**，否则会出现"回答到一半断掉"。

## 9. 命名约定

- JSON 字段：`camelCase`
- 数据库字段：`snake_case`
- SSE event 名：`snake_case`
- 时间：ISO 8601 字符串，如 `2026-11-14T17:30:00+08:00`
- 金额：`number`，单位元，保留两位小数（**不用字符串，不用分**）
