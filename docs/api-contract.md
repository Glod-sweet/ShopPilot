# ShopPilot 三端接口契约

> 本文件是三端唯一的事实来源。前端、Java、Python 全部照此实现，任何字段变更必须先改本文件。

## 1. 服务与端口

| 服务 | 端口 | 对外 | 说明 |
|---|---|---|---|
| pilot-web | 5173 | 是 | Vite 开发服务器 |
| pilot-server | 8080 | **是** | 唯一对外入口，前端只认它 |
| pilot-agent | 8000 | 否 | 内网访问，不暴露给前端 |
| MySQL | 3306 | 否 | |
| PostgreSQL (pgvector) | 5432 | 否 | 商品向量检索 |
| Redis | 6379 | 否 | 会话缓存（可选） |

## 2. 鉴权

- 前端 → Java：`Authorization: Bearer <JWT>`
- Java → Python：`X-Internal-Token: <服务间密钥>` + `X-User-Id: <用户ID>`
- Python → Java（反向工具调用）：同上 `X-Internal-Token`

Python **不解析 JWT**，用户身份完全由 Java 通过 `X-User-Id` 传入。

## 3. Vue → Java

### 3.0 鉴权接口（登录后所有 /api/** 请求都带 token）

| 方法 | 路径 | 说明 | 是否需登录 |
|---|---|---|---|
| POST | `/api/auth/register` | 注册，返回 token | 否 |
| POST | `/api/auth/login` | 登录，返回 token | 否 |
| GET | `/api/auth/me` | 当前用户信息 | 是 |
| GET | `/api/health` | 健康检查（含数据库/Agent 探活） | 否 |

**POST /api/auth/login**

```json
// Request
{ "username": "zhangsan", "password": "123456" }

// Response data
{
  "token": "eyJhbGciOiJIUzM4NCJ9...",
  "expiresIn": 604800,
  "user": { "id": 1, "username": "zhangsan", "nickname": "张三", "phone": "13800000001", "avatarUrl": null }
}
```

**POST /api/auth/register**：`username`（3~32 位字母数字下划线）、`password`（6~64）、`nickname`、`phone` 可选。

> 种子账号：`zhangsan` / `lisi` / `wangwu`，密码统一 `123456`。
> 开发模式下也接受 `Authorization: Bearer dev-token`（以 userId=1 身份），见 `pilot.auth.dev-mode`。

### 3.0.1 商城浏览接口（人找货，不经过 Agent）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/products` | 商品分页列表 |
| GET | `/api/products/{id}` | 商品详情（含 SKU、参数、所属场景） |
| GET | `/api/categories` | 品类树（两级） |
| GET | `/api/brands` | 品牌列表（带在售商品数） |
| GET | `/api/scenes` | 购物场景列表 |

**GET /api/products** 查询参数：

| 参数 | 类型 | 说明 |
|---|---|---|
| categoryId | long | 品类 ID；默认包含其子品类 |
| includeChildren | bool | 默认 true |
| brandId | long | 品牌 |
| minPrice / maxPrice | number | 价格区间 |
| keyword | string | 标题/副标题/卖点模糊匹配 |
| sort | string | `sales`(默认) / `price_asc` / `price_desc` / `rating` / `newest` |
| page / size | int | 默认 1 / 20，size 上限 100 |

返回 `PageResult<ProductCard>`：`{ list, total, page, size, pages }`，`ProductCard` 结构同 4.2 的 `products[]` 元素。

### 3.0.2 购物车与订单

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/cart` | 购物车视图（含实时价格/库存/异常提示） |
| GET | `/api/cart/count` | 购物车条目数（角标用） |
| POST | `/api/cart/items` | 加购 |
| POST | `/api/cart/confirm` | **Agent 建议后的写操作确认入口**（见第 7 节） |
| POST | `/api/cart/items/{id}/quantity?quantity=` | 改数量（≤0 等价删除） |
| POST | `/api/cart/items/{id}/selected?selected=` | 单条勾选 |
| POST | `/api/cart/selected?selected=` | 全选 / 全不选 |
| DELETE | `/api/cart/items/{id}` | 删除条目 |
| POST | `/api/orders` | 下单（扣库存 + 写订单 + 清购物车，单事务） |
| GET | `/api/orders` | 订单列表（`status` 可选过滤） |
| GET | `/api/orders/{id}` | 订单详情 |
| POST | `/api/orders/{id}/cancel` | 取消（仅待付款，回补库存） |
| POST | `/api/orders/{id}/pay` | 支付占位（本项目不接支付渠道） |

**POST /api/cart/items** / **/api/cart/confirm**

```json
{ "productId": 200301, "skuId": null, "quantity": 2, "sessionId": "s_xxx", "agentContext": {} }
```

**POST /api/orders**：`{ "cartItemIds": [1,2], "shipping": {}, "remark": "" }`。
`cartItemIds` 为空表示结算购物车中已勾选的条目。**请求体没有金额字段——金额一律服务端重算**。

**购物车/订单的金额规则**：`totalAmount` 为商品总额；满 99 免运费，否则加 8 元运费得 `payAmount`。

### 3.0.3 会话历史

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/chat/sessions` | 会话列表 |
| GET | `/api/chat/sessions/{sessionNo}` | 会话详情（消息 + 槽位） |
| POST | `/api/chat/click` | 上报推荐点击（算 CTR） |
| GET | `/api/chat/stats?days=7` | 效果统计（CTR / CVR / 平均时延） |

### POST /api/chat/stream

发起对话，返回 SSE 流。

**Request**

```json
{
  "sessionId": "s_20261114_001",
  "message": "想给女朋友买个生日礼物，300以内",
  "clientTraceId": "uuid-v4"
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| sessionId | string | 否 | 为空则新建会话，由响应头 `X-Session-Id` 返回 |
| message | string | 是 | 用户输入，1~2000 字符 |
| clientTraceId | string | 否 | 前端埋点用，原样回传 |

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
{ "sessionId": "s_20261114_001", "messageId": "m_8821", "model": "deepseek-chat" }
```

**token**

```json
{ "text": "根据您的预算，" }
```

**tool_call**

```json
{ "tool": "search_products", "displayText": "正在为您筛选商品…", "args": { "category": "gift", "maxPrice": 300 } }
```

**tool_result**

```json
{ "tool": "search_products", "count": 12, "elapsedMs": 340 }
```

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
{ "messageId": "m_8821", "finishReason": "stop", "totalTokens": 486, "latencyMs": 2140 }
```

**error**

```json
{ "code": "AGENT_TIMEOUT", "message": "服务响应超时，请重试", "retryable": true }
```

错误码约定：

| code | 含义 | 可重试 |
|---|---|---|
| `UNAUTHORIZED` | token 无效 | 否 |
| `RATE_LIMITED` | 触发限流 | 是 |
| `AGENT_TIMEOUT` | Python 超时 | 是 |
| `AGENT_ERROR` | Python 内部异常 | 是 |
| `UPSTREAM_ERROR` | LLM 上游异常 | 是 |

### 4.3 业务接口错误码（HTTP 响应体里的 code）

SSE 流内的错误用上面的字符串码；普通 JSON 接口用下面的数字码，结构为
`{ "code": 0, "message": "ok", "data": {}, "traceId": "..." }`。

| code | HTTP | 含义 |
|---|---|---|
| 0 | 200 | 成功 |
| 1001 | 400 | 参数不合法 |
| 1002 | 401 | 未登录 / token 失效 |
| 1003 | 403 | 无权访问 |
| 1004 | 404 | 资源不存在 |
| 1005 | 409 | 资源状态冲突 |
| 1006 | 429 | 请求过于频繁 |
| 2001 | 409 | 库存不足 |
| 2002 | 400 | 购物车为空 |
| 2003 | 409 | 商品已下架 |
| 2004 | 409 | 订单状态不允许该操作 |
| 3001 | 504 | Agent 超时 |
| 3002 | 502 | Agent 异常 |
| 3003 | 502 | 上游异常 |
| 5000 | 500 | 服务器内部错误 |

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
  "traceId": "uuid-v4"
}
```

| 字段 | 说明 |
|---|---|
| history | 由 Java 从数据库读取后传入，**Python 不查库** |
| slots | 上一轮槽位状态，由 Java 持久化，Python 无状态 |
| traceId | 全链路追踪，Python 日志必须打印 |

### GET /agent/health

```json
{ "status": "ok", "version": "0.1.0", "model": "deepseek-chat" }
```

## 6. Python → Java（反向工具调用）

Agent 的工具通过以下接口取数据。**数据库权限不下放给 Python。**

| 工具名 | 调用接口 | 说明 |
|---|---|---|
| `search_products` | `POST /internal/products/search` | 向量 + 结构化混合检索 |
| `get_product_detail` | `GET /internal/products/{id}` | 单商品详情与参数 |
| `check_stock` | `GET /internal/inventory/{productId}?skuId=` | 实时库存 |
| `get_order_history` | `GET /internal/orders/recent?limit=` | 最近订单，支持"再来一单" |
| `add_to_cart` | `POST /internal/cart/items` | 加购（写操作，仅限内部链路） |
| `get_user_profile` | `GET /internal/users/{id}/profile` | 偏好、历史消费区间 |
| （辅助） | `GET /internal/scenes` | 场景列表，供意图识别 |
| （辅助） | `GET /internal/scenes/{id}/categories` | 场景→品类权重 |

**鉴权**：所有 `/internal/**` 必须带 `X-Internal-Token`，由 `InternalTokenFilter` 统一校验，缺失或错误返回 401。

**用户身份**：只认请求头 `X-User-Id`（由 Java 注入）。**不信任请求体或查询参数里的 userId** —— 否则 Agent 可伪造身份读他人订单。

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
