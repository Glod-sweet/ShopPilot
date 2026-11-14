# 商品页联调说明

> 面向「确认前后端是否连通」这一目标写的操作手册。
> 页面：`pilot-web` 的 `/`（商品目录）与 `/p/:id`（商品详情）。

## 1. 数据来源（先明确这件事）

**没有新增后端接口，也没有 mock 数据。** 页面调的是仓库里已经存在的接口：

| 用途 | 接口 | 代码位置 |
|---|---|---|
| 商品列表 | `GET /api/products` | `catalog/ProductController.java:40` |
| 商品详情 | `GET /api/products/{id}` | `catalog/ProductController.java:59` |
| 品类树 | `GET /api/categories` | `catalog/ProductController.java:65` |

数据落在 MySQL 的 `sp_product` 表，共 **32 条**，其中 `on_shelf=1` 的在售商品 **31 条**。
种子数据见 `pilot-infra/sql/mysql/02_seed.sql`。

> 注意：种子数据里的 `cover_url` 指向 `https://cdn.shoppilot.dev/p/xxx.jpg`，该域名**不存在**。
> 所以页面不加载图片，改用「品类首字 + 派生色块」作视觉锚点。
> 这是刻意的设计决策，不是加载失败——换成真实 CDN 后可直接替换为 `<img>`。

## 2. 本地启动

三个终端，按顺序。

### ① MySQL

```bash
bash pilot-infra/scripts/mysql-start.sh status   # 先看是否已在跑
bash pilot-infra/scripts/mysql-start.sh start    # 前台运行，Ctrl+C 停止
```

### ② 后端（8080）

```bash
cd pilot-server
SERVER__PORT=8080 mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Dserver.port=8080"
```

> `SERVER__PORT=8080` 这个前缀不能省。本机环境变量里存在 `SERVER__PORT=58151`，
> Spring Boot 的宽松绑定会让它覆盖 `application.yml` 里的端口，导致 8080 起不来。

验证：

```bash
curl -H "Authorization: Bearer dev-token" http://localhost:8080/api/health
# {"status":"ok",...,"database":{"status":"up","productCount":32}}
```

### ③ 前端（5173）

```bash
cd pilot-web
npm install
npm run dev
```

打开 <http://localhost:5173/>。

## 3. 跨域与代理配置

**当前走 Vite 代理，前端不直连 8080。**

```
浏览器 ──/api/products──▶ 5173 (Vite) ──代理──▶ 8080 (Spring)
```

`pilot-web/vite.config.ts`：

```ts
server: {
  port: 5173,
  proxy: {
    '/api': { target: 'http://localhost:8080', changeOrigin: true },
  },
}
```

为什么用代理：

1. **同源**。浏览器认为请求发往 5173，不触发 CORS 预检。
2. **代码里没有 host**。`src/api/catalog.ts` 里的 `BASE = '/api'`，部署到 Nginx 后同一条前缀照样成立。
3. 自定义头（`Authorization`）在代理下行为更接近生产。

**后端也配了 CORS**，两条路都能走。见 `pilot-server/.../config/CorsConfig.java`：
放行 `http://localhost:5173`，允许全部方法，`exposedHeaders("X-Session-Id")`。

想验证 CORS 本身是否生效（而不是被代理掩盖）：注释掉 `proxy` 配置，把
`src/api/catalog.ts` 的 `BASE` 改成 `'http://localhost:8080/api'`，刷新页面。
能出数据说明 CORS 正常；报 `blocked by CORS policy` 说明 `allowedOrigins` 与
实际访问地址不一致（比如用了 `127.0.0.1` 而不是 `localhost`，这两个在 CORS 里算不同源）。

## 4. 鉴权

`/api/**` 全部要 token。开发模式放行固定 token，见 `application.yml`：

```yaml
pilot:
  auth:
    dev-mode: true
    dev-token: dev-token
    dev-user-id: 1
```

前端 `src/api/catalog.ts` 默认带 `Authorization: Bearer dev-token`，
对应 `AuthWebFilter` 的 devMode 分支（以 userId=1 身份通过）。

**接真实登录后**：把 token 换成登录接口返回的 JWT，`catalog.ts` 里 `token` 参数已经留好入口。

## 5. 三种状态的验证方法

| 状态 | 怎么触发 | 页面表现 |
|---|---|---|
| 加载中 | 刷新页面；或在 DevTools 里把网络限速设为 Slow 3G | 6 行骨架屏，形状与真实行一致（避免版式跳动） |
| 空数据 | 访问 `/?keyword=zzzznotexist` | 「没有符合条件的商品」+ 清空筛选入口，并注明 `total = 0` 不是故障 |
| 请求失败 | 停掉后端，刷新页面 | 错误态，展示 HTTP 状态、业务码、traceId、耗时，并给排查提示 |

失败态里额外区分了两种成因：

- `status === 0`（连不上）→ 提示检查后端与代理
- `isAuthError`（401/1002）→ 提示检查 `dev-mode` 与 token

## 6. 自动化验证脚本

仓库里有 `pilot-web` 无关的一个验证脚本，用本机 Edge 无头模式跑 10 项检查：

```bash
# 前提：后端 8080 + 前端 5173 都在跑
node .runtime/verify-catalog.mjs
```

覆盖：列表渲染、展开详情、空态、品类筛选 + 价格排序、HTTP 500 失败态、
后端不可达、骨架屏、对话页路由、详情页、详情页 404。

实测输出：

```
PASS  列表渲染  行数=24
PASS  展开详情调用 /api/products/{id}  首个分组=卖点
PASS  空数据态  没有符合条件的商品
PASS  品类筛选 + 价格升序  5 条: 299,449,798,1298,4999
PASS  请求失败态  没能取到商品数据
PASS  后端不可达提示  无法连接后端服务，请确认 pilot-server 已在 8080 启动
PASS  加载中骨架屏
PASS  对话页路由
PASS  详情页 /p/100234  声阔 Soundcore 声浪3 蓝牙耳机
PASS  详情页不存在返回 404  商品不存在
全部通过
```

## 7. 手工联调步骤（推荐按这个顺序走）

1. **确认数据在**：`curl -H "Authorization: Bearer dev-token" "http://localhost:8080/api/products?size=1"`
   → 应返回 `total: 31`。
2. **确认代理通**：`curl "http://localhost:5173/api/products?size=1"`
   → 返回同样的 JSON。**这一步是关键**，它证明 Vite 代理确实转发到了 8080。
3. **打开页面**，看页脚那行灰字：
   ```
   数据来源 GET /api/products   HTTP 200 · 48ms · trace 4fe4378...
   ```
   这行是页面真实发出的请求元信息，不是写死的文案。**HTTP 200 + traceId 出现 = 前后端已连通。**
4. **点任意一行展开**，会再发一次 `GET /api/products/{id}`，面板底部同样标了这条路径。
5. **切品类 / 搜索 / 排序**，观察 URL 变化（条件全部写在 query 里，可直接复制分享）。

## 8. 页面 URL 参数

条件一律写进 URL，刷新和分享都能还原现场：

| 参数 | 说明 | 示例 |
|---|---|---|
| `categoryId` | 品类 ID，默认含子品类 | `?categoryId=101` |
| `keyword` | 标题/副标题/卖点模糊匹配 | `?keyword=耳机` |
| `minPrice` / `maxPrice` | 价格区间 | `?minPrice=100&maxPrice=300` |
| `sort` | `sales`(默认) / `rating` / `price_asc` / `price_desc` | `?sort=price_asc` |
| `page` | 页码，1 省略 | `?page=2` |

后端支持的 `newest` 排序未在页面上暴露——种子数据的时间戳都是同一批导入，
按时间排看不出差异，等有真实增量数据再加。

## 9. 已知边界

- **分页 size 固定 24**，未做「每页条数」选择器。31 条数据两页，够用。
- **详情页 `/p/:id` 与列表内联展开并存**。前者为了可分享，后者为了不丢列表上下文。
- **没有图片**，原因见第 1 节。
- **没有加购物车入口**。加购是写操作，按 `docs/api-contract.md` 第 7 节需要用户二次确认，
  属于交易链路，不在本次「验证连通」的范围内。
