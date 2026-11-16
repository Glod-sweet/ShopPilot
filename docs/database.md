# ShopPilot 数据库说明

> 面向接手者：本文记录**为什么这样建库**、**怎么起**、**哪些坑已经踩过**。
> Schema 见 `pilot-infra/sql/mysql/01_schema.sql`，种子数据见 `02_seed.sql`。

---

## 一、快速开始

```bash
# 1. 启动 MySQL（前台运行，Ctrl+C 停止）
bash pilot-infra/scripts/mysql-start.sh start

# 2. 另开终端，确认端口与数据
bash pilot-infra/scripts/mysql-start.sh status
bash pilot-infra/scripts/mysql-start.sh check

# 3. 首次初始化（会重建所有表，慎用）
mysql -h 127.0.0.1 -P 3306 -u root -p1234 --default-character-set=utf8mb4 < pilot-infra/sql/mysql/01_schema.sql
mysql -h 127.0.0.1 -P 3306 -u root -p1234 --default-character-set=utf8mb4 < pilot-infra/sql/mysql/02_seed.sql
```

**连接信息**

| 项 | 值 |
|---|---|
| Host / Port | `127.0.0.1` / `3306` |
| Database | `shop_pilot` |
| User / Password | `root` / `1234` |
| 字符集 | `utf8mb4` / `utf8mb4_0900_ai_ci` |

> 执行 SQL 时必须带 `--default-character-set=utf8mb4`，否则 Windows 终端下中文会乱码。

---

## 一之一、终端环境（必读，否则脚本会误报）

**脚本必须在 Git Bash 里运行，不能在 PowerShell 里直接 `bash`。**

原因：PowerShell 里的 `bash` 命令会调起 `C:\Windows\System32\bash.exe`，
那是 **WSL（Linux 子系统）**，不是 Git Bash。脚本用的 `netstat -ano` / `tasklist`
在 Linux 下都不工作，会得到**假报错**：

| 你看到的 | 真实原因 |
|---|---|
| `[DOWN] 3306 端口无监听` | Linux 的 netstat 不认 `-ano`，返回空 |
| `[INFO] 没有运行中的实例` | Linux 没有 `tasklist` 命令 |
| `[FAIL] 找不到 mysqld` | Linux 里没有 `D:` 盘符 |

**这三个报错全是假象**，MySQL 可能好好地在跑。

### 已做的修复：DataGrip 终端默认改成 Git Bash

配置写在 `%APPDATA%\JetBrains\DataGrip2026.1\options\terminal.xml`：

```xml
<application>
  <component name="TerminalOptionsProvider">
    <option name="myShellPath" value="C:\Users\zhang\.workbuddy-ai\binaries\PortableGit\versions\1.3.0\bin\bash.exe" />
  </component>
</application>
```

**生效方式**：重启 DataGrip，之后底部 Terminal 打开就是 Git Bash，提示符是
`user@host MINGW64 /d/Git/ShopPilot`，而不是 `PS D:\Git\ShopPilot>`。

判断方法：提示符里带 **`PS`** 就是 PowerShell（错），带 **`MINGW64`** 才是 Git Bash（对）。

### 脚本本身也做了加固

`mysql-start.sh` 现在会主动把 `System32` 补进 PATH，并用绝对路径调用
`netstat.exe` / `tasklist.exe` / `taskkill.exe`。

为什么需要：Git Bash 的这些命令其实是转发到 `C:\Windows\System32\*.exe`，
如果 IDE 启动终端时环境变量不完整，会报 `netstat: command not found`，
同样导致误判成「端口无监听」。加固后即使 `env -i` 清空环境也能正确工作。

### 如果不想改终端

在 PowerShell 里用原生命令等价实现：

```powershell
# 查看状态
netstat -ano | Select-String ":3306.*LISTENING"

# 停止
Get-Process mysqld -ErrorAction SilentlyContinue | Stop-Process -Force
```

---

## 一之二、DataGrip 连接

数据源已预置在 `.idea/dataSources.xml` + `.idea/dataSources.local.xml`，**不需要手动填连接串**。

**接入步骤**

1. DataGrip → `File → Open` → 选 `D:/Git/ShopPilot`（当前 DataGrip 打开的是别的项目，需要切过来）
2. 左侧 Database 面板会出现 `shop_pilot@localhost`
3. 首次点 Connect 弹密码框 → 输入 `1234` → 勾选 **Save password**

**密码为什么不写在文件里**

DataGrip 用 KeePass 加密库存密码（配置目录下的 `c.kdbx`，主密钥由 IDE 管理）。
外部写入明文或伪造密文都无效，只能首次连接时手动输一次。
所以配置文件里只有连接参数和用户名，没有密码 —— 这是正常的，不是漏了。

---

### ⚠️ 症状：数据源在列表里，但展开什么都没有

这是实际踩到的，**不是配置写错了**。三个特征可以确诊：

| 观察点 | 正常 | 出问题时 |
|---|---|---|
| 数据源图标颜色 | 绿色 | **浅蓝色**（`RGB(0,175,255)`，表示未连接） |
| `.idea/dataSources/<uuid>.xml` | 存在，几十 KB 的 schema 模型 | **不存在** |
| 日志 `idea.log` | 有 `Connecting to: jdbc:...` | 只有 `No model loaded for: xxx`，**没有任何连接尝试** |

日志位置：`C:\Users\zhang\AppData\Local\JetBrains\DataGrip2026.1\log\idea.log`

**根因**：密码从未保存，DataGrip 没有凭据所以根本不去连。
验证方法——看密码库时间戳：

```bash
ls -la "$APPDATA/JetBrains/DataGrip2026.1/c.kdbx"   # 密码库最后写入时间
grep -c "<你的数据源uuid>" "$APPDATA/JetBrains/DataGrip2026.1/c.pwd"   # 0 = 没存过密码
```

也可以从 MySQL 侧反查，最直接：

```sql
SELECT id, user, host, db, command, time FROM information_schema.processlist;
-- 如果只有自己的连接，说明 DataGrip 从没连上来过
```

**解决**：在 Database 面板右键数据源 → `Properties` → 填 Password → 勾 `Save password` → 点 `Test Connection`。
如果点 Connect 没弹密码框，就走 Properties 这条路，一定能输入。

**另一个坑：不要预填 `<database-info>`**

`dataSources.local.xml` 里的 `<database-info product="MySQL" version="9.7.0" ...>`
是 introspection **成功后**由 IDE 写入的探测结果，不是配置项。

预先写死它会让 DataGrip 以为已经探测过，从而跳过首次连接 —— 表现同样是「列表里有、展开是空的」。
正确做法是**完全不写这个元素**，让 IDE 连上后自己填。

---

**JDBC URL 参数逐项说明**（每个都是踩坑后加的）

```
jdbc:mysql://127.0.0.1:3306/shop_pilot
  ?useUnicode=true
  &characterEncoding=UTF-8        ← 必须写 UTF-8，不能写 utf8mb4！
  &serverTimezone=Asia/Shanghai
  &allowPublicKeyRetrieval=true
  &useSSL=false
  &tinyInt1isBit=false
```

| 参数 | 作用 | 不写的后果 |
|---|---|---|
| `characterEncoding=UTF-8` | 驱动内部会映射到 MySQL 的 utf8mb4 | 写 `utf8mb4` 会直接报 **`Unsupported character encoding 'utf8mb4'`**（Connector/J 不认这个名字） |
| `serverTimezone=Asia/Shanghai` | 固定时区 | DATETIME 显示偏移 8 小时 |
| `allowPublicKeyRetrieval=true` | MySQL 8+ 默认 `caching_sha2_password` 认证 | 报 `Public Key Retrieval is not allowed` |
| `useSSL=false` | 本地连接免证书握手 | 多一次无意义的 TLS 协商 |
| `tinyInt1isBit=false` | TINYINT(1) 按数字读（0/1） | DataGrip 显示 `true/false`，后端是 `0/1`，排查时误判数据不一致 |

**`tinyInt1isBit=false` 这条特别重要**：后端 `R2dbcConfig` 也设了同样的参数。
如果两边不一致，同一个 `on_shelf` 字段在 DataGrip 里看是 `1`、在日志里是 `true`，
会浪费很多时间去查一个根本不存在的 bug。

**只 introspect `shop_pilot` 库**

`dataSources.local.xml` 里的 `introspection-scope` 只勾了 `shop_pilot`。
本机 MySQL 还有 `mall` `tlias` `sky_take_out` 等 18 个库，全量 introspect 会很慢，
左侧树也全是噪音。

**配置自检**

不想打开 DataGrip 也能验证配置是否可用：

```bash
bash pilot-infra/scripts/mysql-start.sh check
# 连库并统计商品数，输出形如 product_cnt 32
```

期望输出 `PASS` 与商品总数。

---

## 二、为什么 3306 之前建不了数据源

排查结论（三层原因叠加，不是端口冲突）：

| # | 现象 | 根因 | 处理 |
|---|---|---|---|
| 1 | `Start-Service MySQL` 报 `Cannot open MySQL service` | 服务已注册（StartMode=Auto）但处于 **Stopped**，当前用户**无管理员权限**启动 | 不依赖服务，直接跑 mysqld 进程 |
| 2 | 直接跑 mysqld 后 abort | 默认 PID 文件名取**主机名**，本机主机名是中文，被编码成 `??????_.pid` → `Permission denied` | 显式 `--pid-file=<ASCII路径>` |
| 3 | 启动成功后端口随即消失 | 后台进程被父 shell 回收，日志里有 `ready for connections` 但没有 shutdown 记录 | 改为**前台阻塞运行**（`exec mysqld`），由终端持有 |

脚本已封装上述三点，直接用 `mysql-start.sh start` 即可。

> 如果将来拿到管理员权限，可以用 `sc config MySQL start= auto` + `net start MySQL` 恢复成服务方式，届时脚本可以简化。

---

## 二之二、R2DBC 接入的四个坑（已踩，均已修复）

Java 侧用 `spring-boot-starter-data-r2dbc` + `io.asyncer:r2dbc-mysql` 接 MySQL。
这几个坑都是联调时**真实触发**的，不是预防性猜测：

| # | 现象 | 根因 | 修复 |
|---|---|---|---|
| 1 | 加购接口对**任何商品**都返回「商品已下架」 | R2DBC MySQL 驱动默认把 `TINYINT(1)` 读成 `Boolean`，而代码写的是 `instanceof Number && intValue()==1` → 恒为 false | `R2dbcConfig` 显式 `.tinyInt1isBit(false)`，让 TINYINT(1) 保持数值；同时判断逻辑兼容 Boolean/Number/String 三种形态 |
| 2 | 检索接口报 `Value for parameter traceId must not be null` | `DatabaseClient.bind(name, null)` 会直接抛异常，**不会**把 NULL 传给数据库 | 新增 `SafeBind` 工具类，null 时自动走 `bindNull`；埋点/消息落库全部改用它 |
| 3 | `r2dbc-mysql` 编译期找不到 `io.asyncer.r2dbc.mysql` | pom 里标了 `<scope>runtime</scope>`，而 `R2dbcConfig` 需要 import 它的类 | 去掉 runtime 作用域 |
| 4 | `.map(...).collectList()` 编译失败 | `.map()` 返回的是 `RowsFetchSpec`，不是 `Flux`，没有 `collectList()` | 先 `.all()` 转成 Flux 再聚合 |

另外两处易错点：

- `MySqlConnectionConfiguration.builder().serverZoneId(...)` 收的是 **`ZoneId`** 而非 `String`
- `bind("x", (int) someLong)` 不合法：`Long` 不能直接窄化成 `int`，需要 `longValue()` 或 `intValue()`

---

## 三、MySQL 9.7 的三个兼容性陷阱（已实测）

本项目本机是 **MySQL 9.7.0 Community**，与常见教程的 5.7/8.0 有差异，已踩：

| 差异点 | 现象 | 应对 |
|---|---|---|
| `MD5()` 已移除 | `FUNCTION shop_pilot.MD5 does not exist` | 改用 `LEFT(SHA2(x, 256), 32)` 作内容指纹 |
| `VECTOR` 类型存在，但 **`DISTANCE()` / `vector_distance()` 不可用** | `FUNCTION ... DISTANCE does not exist` | 余弦相似度在 **Java 应用层**计算，`embedding` 字段存 JSON |
| 向量列 **不能建索引**（`using hnsw` 语法报错，BTREE 报 `Non-scalar column cannot be used as key`） | 建索引失败 | 同上，召回在应用层做；数据量上去后切 PostgreSQL + pgvector |

**这直接影响了架构决策**：原方案（handbook 4.3）计划用 PostgreSQL + pgvector 做向量检索，但本机 Docker 未安装、PG 起不来。因此当前实现为：

```
MySQL 存业务数据 + 向量原料(content) + 向量值(embedding JSON)
        ↓
Java 层读候选集（结构化过滤先做，把候选压到几百条）
        ↓
应用层算余弦相似度并重排序
        ↓
返回 top5 给 LLM 生成推荐理由
```

**这个降级方案在 10 万商品以内完全够用**，且省掉一个中间件。`sp_product_embedding` 的表结构刻意与 pgvector 版本保持同构（`product_id / content / embedding / category_id / price / on_shelf / stock`），将来迁移只需换存储实现，上层代码不动。

---

## 四、表设计要点

### 4.1 六域划分

| 域 | 表 | 说明 |
|---|---|---|
| 用户 | `sp_user` `sp_user_profile` | 画像供 `get_user_profile` 工具做个性化 |
| 商品 | `sp_category` `sp_brand` `sp_product` `sp_sku` `sp_product_embedding` | 检索所需字段（价格/库存/上架）在商品表与向量表**双写** |
| **场景** | `sp_scene` `sp_scene_category` `sp_product_scene` | **本项目的差异化核心**，见下 |
| 会话 | `sp_chat_session` `sp_chat_message` `sp_chat_slot` | 槽位持久化在 Java 侧，Python 无状态 |
| 交易 | `sp_cart_item` `sp_order` `sp_order_item` | 写操作只由 Java 执行 |

### 4.2 为什么单独做「场景域」

handbook 4.2 说场景化选品的难点是「意图到商品的语义映射，纯关键词搜索做不到」。纯向量检索也做不到——用户说「给女朋友买生日礼物」，向量空间里最接近的是「礼物」这个词，而不是「香薰/口红/项链」这些**该被推荐的品类**。

所以加了三张表做**显式映射**：

```
sp_scene            场景本体：birthday_gift / running / camping ...
   ↓ keywords      "生日,礼物,送女友" —— 用于从用户输入里识别场景
sp_scene_category   场景 → 值得推荐的品类 + 权重
   ↓
sp_product_scene    商品 → 场景 + 契合度 score + 场景内推荐理由
```

这样「生日礼物 300 以内」的执行路径是：
1. 关键词命中 `birthday_gift`
2. 该场景的品类是 `香薰(100) / 口红(90) / 项链(80) / 手链(75) / 香水(70)`
3. 在这些品类内按 `price<=300 AND on_shelf=1 AND stock>0` 过滤
4. 按 `score DESC` 排序

**这条路不依赖 embedding，冷启动就能用**；embedding 用来处理「木质调」「适合跑步」这类细粒度语义。

### 4.3 双写冗余的原因

`sp_product` 和 `sp_product_embedding` 都存了 `price / stock / on_shelf`。原因是 handbook 4.3 提到的：**先向量召回再回表过滤会导致召回数量不够**。冗余后过滤与召回能在一次查询里完成。

代价是要保证一致性——当前由 `02_seed.sql` 全量重建保证；接真实数据后建议用**触发器或定时同步**，不要让两边漂移。

---

## 五、种子数据设计：每一类都能驱动一个验收场景

数据不是随便填的，**32 个商品里有 3 个是故意埋的陷阱**，用来验证混合检索的过滤能力：

| 陷阱商品 | 价格 | 库存 | 上架 | 为什么埋 |
|---|---|---|---|---|
| 声阔 Space Q45 限量典藏版 | 4999 | 3 | 是 | 纯向量检索遇到「耳机」必召回，必须被**价格**过滤 |
| 韶音 OpenRun Pro 2 | 1298 | **0** | 是 | 语义与「跑步耳机」高度相关，必须被**库存**过滤 |
| 素野 圣诞限定香薰礼盒 | 238 | 20 | **否** | 标题与「生日礼物香薰」高度匹配，必须被**上架状态**过滤 |

**验收方式**：执行下面的查询，结果里不应该出现这 3 个商品。

```sql
-- 场景1：生日礼物 300 以内
SELECT p.id, p.title, p.price, p.stock, ps.score, ps.reason
FROM sp_product p
JOIN sp_product_scene ps ON ps.product_id = p.id
WHERE ps.scene_id = 1 AND p.price <= 300
  AND p.on_shelf = 1 AND p.stock > 0
ORDER BY ps.score DESC, p.rating DESC;
```

实测返回 8 条，陷阱商品全部被拦截（100238 同时命中「价格超预算」和「已售罄」，说明过滤条件必须叠加）。

### 5.1 三个场景的数据支撑

| 场景 | 用户输入 | 依赖的表 | 实测结果 |
|---|---|---|---|
| S1 场景化选品 | 想给女朋友买个生日礼物，300以内 | `sp_scene` + `sp_scene_category` + `sp_product_scene` | 8 个候选，按场景契合度排序 |
| S2 多品对比 | 500以内的跑步耳机 | 同上 + `sp_product.attributes` | 2 款可对比（声浪4 449 / 声浪3 299），防水等级与佩戴方式可直接对比 |
| S3 交易代办 | 上次买的洗衣液再来一单 | `sp_order` + `sp_order_item` | 查到 2 笔历史订单，商品仍在架有库存 |

### 5.2 中文 JSON 键的坑

`sp_product.attributes` 用了中文键（`"防水等级"`、`"佩戴方式"`），查询时**必须给键名加双引号**：

```sql
-- 错误：Invalid JSON path expression
p.attributes->>'$.防水等级'

-- 正确
JSON_UNQUOTE(JSON_EXTRACT(p.attributes, '$.`防水等级`'))
-- 或
JSON_UNQUOTE(JSON_EXTRACT(p.attributes, '$."防水等级"'))
```

在 shell 里传这条 SQL 还要额外转义，建议用 `-D shop_pilot` 指定库并把 SQL 写进 `.sql` 文件执行。

---

## 六、表清单与数据量

| 表 | 行数 | 说明 |
|---|---|---|
| `sp_user` | 3 | 张三/李四/王五，对应三个不同消费画像 |
| `sp_user_profile` | 3 | 预算区间与偏好品类不同，用于验证个性化 |
| `sp_category` | 22 | 5 个一级 + 17 个二级 |
| `sp_brand` | 12 | |
| `sp_product` | 32 | 含 3 个陷阱商品 |
| `sp_sku` | 15 | 部分商品多规格 |
| `sp_product_embedding` | 32 | `embedding` 为 NULL，待回填 |
| `sp_scene` | 6 | birthday_gift / anniversary / running / camping / daily_replenish / office |
| `sp_scene_category` | 21 | |
| `sp_product_scene` | 28 | 含场景内推荐理由 |
| `sp_chat_session` | 3 | 三个场景各一条，供前端历史回看联调 |
| `sp_chat_message` | 12 | 含 `product_card` / `slot_ask` / `tool_call` 的 payload 样例 |
| `sp_chat_slot` | 3 | |
| `sp_order` | 4 | 含 1 笔 `source='agent'`（AI 导购成单归因） |
| `sp_order_item` | 4 | |
| `sp_cart_item` | 1 | |

---

## 七、下一步：向量回填

`sp_product_embedding.embedding` 当前全为 NULL（`dim=0`, `model='pending'`）。回填流程：

1. 从 `sp_product_embedding.content` 读出文本（已按「标题 | 副标题 | 卖点 | 品类 | 品牌 | 场景」拼好）
2. 调 embedding 接口（DeepSeek/通义/OpenAI 均可，维度需统一，建议 1024）
3. 写回 `embedding`（JSON 数组）、`dim`、`model`，并更新 `content_hash`

**增量更新**：只处理 `content_hash` 变化的行，避免全量重算。

---

## 八、向量存储的当前形态与升级路径

当前实现：**MySQL 单库 + 应用层算余弦相似度**，0 个额外中间件。

原计划的 PostgreSQL + pgvector 方案已移除（本机无 Docker，PG 起不来，且 MySQL 社区版
不支持向量索引）。`sp_product_embedding` 的表结构刻意与 pgvector 版本保持同构
（`product_id / content / embedding / category_id / price / on_shelf / stock`），
将来迁移只需把「读 MySQL + 应用层算」换成「查 PG」，上层代码不动。

- 升级触发条件：商品量 > 10 万，或应用层相似度计算成为瓶颈
- 迁移成本：换掉 `ProductRepository.findCandidates` 与 `HybridSearchService.rank` 两处

---

## 九、联调验证记录（2026-11-14 实测通过）

Java 侧已从 mock 数据切到真实库，以下为实际跑通的结果。

### 9.1 健康检查

```bash
curl --noproxy localhost http://127.0.0.1:8080/api/health
# {"status":"ok","service":"pilot-server","version":"0.1.0",
#  "database":{"status":"up","productCount":32}}
```

健康检查**带数据库探活**，能区分「服务挂了」和「库连不上」。

### 9.2 三个场景

```bash
TOKEN=dev-internal-token-change-me
curl --noproxy localhost -X POST http://127.0.0.1:8080/internal/products/search \
  -H "Content-Type: application/json" -H "X-Internal-Token: $TOKEN" \
  -d '{"query":"想给女朋友买个生日礼物，300以内","maxPrice":300,"limit":3}'
```

| 场景 | 识别结果 | 候选数 | Top3 |
|---|---|---|---|
| 生日礼物 300 内 | `birthday_gift` | 8 | 木质调香薰礼盒 268 / 同心锁口红 219 / 白茶香薰 158 |
| 500 内跑步耳机 | `running` | 4 | 声浪4 449 / 赤兔7 PRO 499 / 声浪3 299 |
| 再来一单 | — | — | 查到 2 笔历史订单，带 `anomaly` 字段 |

**关键点**：场景识别靠 `sp_scene.keywords` 关键词命中。踩过一个坑——
MySQL 的 JSON 列经 R2DBC 返回的是 **String 而非 List**，最初用 `instanceof List`
判断导致场景永远识别不出来，检索退化成全表按评分排序，于是「买礼物」被推荐了洗衣液。
修复方式是自己解析 JSON 字符串数组（见 `HybridSearchService.parseJsonArray`）。

### 9.3 陷阱商品拦截（全部 PASS）

| 陷阱 | 拦截依据 | 结果 |
|---|---|---|
| 4999 元限量耳机 | 价格 > 500 | 未出现 |
| 售罄跑步耳机 | `stock = 0` | 未出现 |
| 已下架香薰礼盒 | `on_shelf = 0` | 未出现 |

### 9.4 写操作校验

| 请求 | 返回 |
|---|---|
| 售罄商品加购 | `2001 库存不足，当前仅剩 0 件` |
| 已下架商品加购 | `2003 商品已下架` |
| 正常商品加购 ×2 | `code=0, cartItemId=2` |
| 超量（99 件，库存 3） | `2001 库存不足，当前仅剩 3 件` |

写操作要求 `X-User-Id` 头（由内部鉴权过滤器校验），Python 侧需带上。

