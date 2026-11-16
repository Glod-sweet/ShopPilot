-- =============================================================
-- ShopPilot 业务库 Schema（MySQL 8.0+ / 9.x 通用）
-- 库名：shop_pilot
-- 设计依据：docs/api-contract.md + docs/project-handbook.md
--
-- 分层：
--   用户域  sp_user / sp_user_profile
--   商品域  sp_category / sp_brand / sp_product / sp_sku / sp_product_embedding
--   场景域  sp_scene / sp_scene_category / sp_product_scene   ← 导购项目的差异化核心
--   会话域  sp_chat_session / sp_chat_message / sp_chat_slot  ← 槽位持久化在 Java 侧
--   交易域  sp_cart_item / sp_order / sp_order_item
-- =============================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

CREATE DATABASE IF NOT EXISTS shop_pilot
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_0900_ai_ci;

USE shop_pilot;

-- 幂等：按依赖倒序清理
-- 埋点视图已废弃（sp_recommend_log 表一并移除），但旧库可能残留，这里兜底清掉
DROP VIEW IF EXISTS sp_v_recommend_daily;
DROP TABLE IF EXISTS sp_order_item;
DROP TABLE IF EXISTS sp_order;
DROP TABLE IF EXISTS sp_cart_item;
DROP TABLE IF EXISTS sp_chat_slot;
DROP TABLE IF EXISTS sp_chat_message;
DROP TABLE IF EXISTS sp_chat_session;
DROP TABLE IF EXISTS sp_product_embedding;
DROP TABLE IF EXISTS sp_product_scene;
DROP TABLE IF EXISTS sp_scene_category;
DROP TABLE IF EXISTS sp_scene;
DROP TABLE IF EXISTS sp_sku;
DROP TABLE IF EXISTS sp_product;
DROP TABLE IF EXISTS sp_brand;
DROP TABLE IF EXISTS sp_category;
DROP TABLE IF EXISTS sp_user_profile;
DROP TABLE IF EXISTS sp_user;

-- -------------------------------------------------------------
-- 一、用户域
-- -------------------------------------------------------------
CREATE TABLE sp_user (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    username    VARCHAR(64)  NOT NULL COMMENT '登录名',
    nickname    VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '昵称',
    phone       VARCHAR(20)           DEFAULT NULL COMMENT '手机号',
    avatar_url  VARCHAR(500)          DEFAULT NULL COMMENT '头像',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '1正常 0禁用',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE = InnoDB COMMENT = '用户';

-- Agent 个性化推荐要用：预算区间、偏好品类、历史客单
CREATE TABLE sp_user_profile (
    user_id              BIGINT        NOT NULL COMMENT '用户ID',
    budget_min           DECIMAL(10, 2)         DEFAULT NULL COMMENT '常购预算下限',
    budget_max           DECIMAL(10, 2)         DEFAULT NULL COMMENT '常购预算上限',
    preferred_categories JSON                   DEFAULT NULL COMMENT '偏好品类ID数组 [203,201]',
    preferred_brands     JSON                   DEFAULT NULL COMMENT '偏好品牌ID数组',
    avg_order_amount     DECIMAL(10, 2) NOT NULL DEFAULT 0 COMMENT '平均客单价',
    order_count          INT            NOT NULL DEFAULT 0 COMMENT '累计订单数',
    last_active_at       DATETIME                DEFAULT NULL COMMENT '最近活跃时间',
    updated_at           DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id)
) ENGINE = InnoDB COMMENT = '用户画像（供 get_user_profile 工具使用）';

-- -------------------------------------------------------------
-- 二、商品域
-- -------------------------------------------------------------
CREATE TABLE sp_category (
    id        BIGINT      NOT NULL COMMENT '品类ID（一级 1~99，二级 101+）',
    parent_id BIGINT      NOT NULL DEFAULT 0 COMMENT '父品类，0为一级',
    name      VARCHAR(64) NOT NULL COMMENT '品类名',
    level     TINYINT     NOT NULL DEFAULT 1 COMMENT '层级 1/2',
    sort      INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_parent (parent_id)
) ENGINE = InnoDB COMMENT = '商品品类';

CREATE TABLE sp_brand (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    name        VARCHAR(64)  NOT NULL,
    logo_url    VARCHAR(500)          DEFAULT NULL,
    description VARCHAR(255)          DEFAULT NULL,
    sort        INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_name (name)
) ENGINE = InnoDB COMMENT = '品牌';

-- 检索所需字段（价格/库存/上架）在本表与 sp_product_embedding 双写，
-- 目的：结构化过滤与向量召回能在一次查询里完成（见 handbook 4.3）
CREATE TABLE sp_product (
    id             BIGINT        NOT NULL COMMENT '商品ID（对外即 productId）',
    product_no     VARCHAR(64)   NOT NULL COMMENT '商品编码 P100234',
    title          VARCHAR(200)  NOT NULL COMMENT '标题',
    sub_title      VARCHAR(255)           DEFAULT NULL COMMENT '副标题/卖点一句话',
    category_id    BIGINT        NOT NULL COMMENT '品类ID',
    brand_id       BIGINT        NOT NULL DEFAULT 0 COMMENT '品牌ID',
    price          DECIMAL(10, 2) NOT NULL COMMENT '现价（元）',
    original_price DECIMAL(10, 2)          DEFAULT NULL COMMENT '划线价（元）',
    cover_url      VARCHAR(500)           DEFAULT NULL COMMENT '主图',
    album          JSON                   DEFAULT NULL COMMENT '图片集',
    tags           JSON                   DEFAULT NULL COMMENT '标签 ["礼物包装","顺丰包邮"]',
    selling_points TEXT                   DEFAULT NULL COMMENT '卖点长文，向量化原料之一',
    description    TEXT                   DEFAULT NULL COMMENT '详情',
    attributes     JSON                   DEFAULT NULL COMMENT '参数 {"防水等级":"IPX7"}',
    stock          INT           NOT NULL DEFAULT 0 COMMENT '可售库存',
    sales_count    INT           NOT NULL DEFAULT 0 COMMENT '累计销量（重排序用）',
    rating         DECIMAL(3, 2) NOT NULL DEFAULT 5.00 COMMENT '评分 0~5（重排序用）',
    comment_count  INT           NOT NULL DEFAULT 0 COMMENT '评价数',
    on_shelf       TINYINT(1)    NOT NULL DEFAULT 1 COMMENT '是否上架（过滤条件）',
    gift_wrappable TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '是否可礼品包装（场景化选品）',
    created_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_product_no (product_no),
    KEY idx_filter (on_shelf, category_id, price),
    KEY idx_sales (sales_count),
    KEY idx_brand (brand_id)
) ENGINE = InnoDB COMMENT = '商品主表';

CREATE TABLE sp_sku (
    id         BIGINT        NOT NULL AUTO_INCREMENT,
    product_id BIGINT        NOT NULL COMMENT '所属商品',
    sku_code   VARCHAR(64)   NOT NULL COMMENT 'SKU编码',
    spec       JSON                   DEFAULT NULL COMMENT '规格 {"颜色":"雾霾蓝","容量":"50ml"}',
    price      DECIMAL(10, 2) NOT NULL COMMENT 'SKU价',
    stock      INT           NOT NULL DEFAULT 0 COMMENT 'SKU库存',
    image_url  VARCHAR(500)           DEFAULT NULL,
    status     TINYINT       NOT NULL DEFAULT 1 COMMENT '1可售 0下架',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sku_code (sku_code),
    KEY idx_product (product_id)
) ENGINE = InnoDB COMMENT = '商品SKU';

-- 向量表：content 是向量化原料，embedding 由离线任务写入
-- 注意：MySQL 社区版无 DISTANCE()/HNSW（已实测 9.7.0 不支持），
--       余弦相似度在 Java 应用层计算，故 embedding 存 JSON 便于调试。
--       后续若启用 PostgreSQL+pgvector，仅需替换本表实现。
CREATE TABLE sp_product_embedding (
    product_id  BIGINT        NOT NULL COMMENT '商品ID',
    content     TEXT          NOT NULL COMMENT '向量化文本：标题+卖点+属性+品类+场景',
    content_hash CHAR(32)              DEFAULT NULL COMMENT 'content 的 MD5，避免重复向量化',
    embedding   JSON                   DEFAULT NULL COMMENT '向量数组，长度=dim；NULL 表示待生成',
    dim         SMALLINT      NOT NULL DEFAULT 0 COMMENT '向量维度，0=未生成',
    model       VARCHAR(64)   NOT NULL DEFAULT 'pending' COMMENT '生成模型标识',
    category_id BIGINT        NOT NULL COMMENT '冗余，供过滤',
    brand_id    BIGINT        NOT NULL DEFAULT 0 COMMENT '冗余，供过滤',
    price       DECIMAL(10, 2) NOT NULL COMMENT '冗余，供价格区间过滤',
    on_shelf    TINYINT(1)    NOT NULL DEFAULT 1 COMMENT '冗余，供过滤',
    stock       INT           NOT NULL DEFAULT 0 COMMENT '冗余，供过滤',
    updated_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (product_id),
    KEY idx_filter (on_shelf, category_id, price)
) ENGINE = InnoDB COMMENT = '商品向量（混合检索的召回源）';

-- -------------------------------------------------------------
-- 三、场景域：把「抽象意图」映射到「具体品类」
-- 纯向量做不到的部分，靠这三张表补齐（handbook 4.2 场景化选品）
-- -------------------------------------------------------------
CREATE TABLE sp_scene (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    code           VARCHAR(32)  NOT NULL COMMENT '场景编码 birthday_gift / camping ...',
    name           VARCHAR(64)  NOT NULL COMMENT '场景名',
    description    VARCHAR(255)          DEFAULT NULL,
    keywords       JSON                  DEFAULT NULL COMMENT '触发词 ["生日","礼物","送女友"]',
    budget_hint_min DECIMAL(10, 2)        DEFAULT NULL COMMENT '该场景常见预算下限（槽位追问用）',
    budget_hint_max DECIMAL(10, 2)        DEFAULT NULL COMMENT '该场景常见预算上限',
    sort           INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_code (code)
) ENGINE = InnoDB COMMENT = '购物场景';

CREATE TABLE sp_scene_category (
    scene_id    BIGINT NOT NULL,
    category_id BIGINT NOT NULL COMMENT '该场景下值得推荐的品类',
    weight      INT    NOT NULL DEFAULT 100 COMMENT '场景内品类权重，越大越优先',
    PRIMARY KEY (scene_id, category_id)
) ENGINE = InnoDB COMMENT = '场景→品类映射（意图落地的关键一步）';

CREATE TABLE sp_product_scene (
    product_id BIGINT       NOT NULL,
    scene_id   BIGINT       NOT NULL,
    score      DECIMAL(4, 3) NOT NULL DEFAULT 0.500 COMMENT '场景契合度 0~1',
    reason     VARCHAR(255)          DEFAULT NULL COMMENT '该场景下的一句话推荐理由',
    PRIMARY KEY (product_id, scene_id),
    KEY idx_scene (scene_id, score)
) ENGINE = InnoDB COMMENT = '商品→场景（含推荐理由）';

-- -------------------------------------------------------------
-- 四、会话域
-- 原则：Java 是唯一有状态层，槽位/历史都落库，Python 每次带全量上下文进来
-- -------------------------------------------------------------
CREATE TABLE sp_chat_session (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    session_no      VARCHAR(64)  NOT NULL COMMENT '对外 sessionId s_xxx',
    user_id         BIGINT       NOT NULL,
    title           VARCHAR(255) NOT NULL DEFAULT '' COMMENT '会话标题，取首条用户消息',
    status          TINYINT      NOT NULL DEFAULT 1 COMMENT '1进行中 0已结束',
    message_count   INT          NOT NULL DEFAULT 0,
    last_message_at DATETIME              DEFAULT NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_session_no (session_no),
    KEY idx_user (user_id, last_message_at)
) ENGINE = InnoDB COMMENT = '会话';

CREATE TABLE sp_chat_message (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    message_no VARCHAR(64)  NOT NULL COMMENT '对外 messageId m_xxx',
    session_id BIGINT       NOT NULL,
    role       VARCHAR(16)  NOT NULL COMMENT 'user / assistant / tool',
    content    TEXT                  DEFAULT NULL COMMENT '文本内容',
    event_type VARCHAR(32)           DEFAULT NULL COMMENT '对应SSE: product_card/slot_ask 等',
    payload    JSON                  DEFAULT NULL COMMENT '卡片数据、追问选项等结构化内容',
    tokens     INT          NOT NULL DEFAULT 0,
    latency_ms INT          NOT NULL DEFAULT 0,
    trace_id   VARCHAR(64)           DEFAULT NULL,
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_message_no (message_no),
    KEY idx_session (session_id, id)
) ENGINE = InnoDB COMMENT = '消息（同时承载流式结束后的落库）';

-- 槽位状态机：Java 持久化 → 每轮随请求传给 Python
CREATE TABLE sp_chat_slot (
    session_id    BIGINT        NOT NULL,
    category_id   BIGINT                 DEFAULT NULL COMMENT '必填槽位：品类',
    category_name VARCHAR(64)            DEFAULT NULL COMMENT '品类原始说法，如"生日礼物"',
    budget_min    DECIMAL(10, 2)         DEFAULT NULL COMMENT '必填槽位：预算下限',
    budget_max    DECIMAL(10, 2)         DEFAULT NULL COMMENT '必填槽位：预算上限',
    scene_code    VARCHAR(32)            DEFAULT NULL COMMENT '选填：场景',
    brand_id      BIGINT                 DEFAULT NULL COMMENT '选填：品牌偏好',
    extra         JSON                   DEFAULT NULL COMMENT '其他扩展槽位',
    is_complete   TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '必填槽位是否齐备（齐了才检索）',
    updated_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (session_id)
) ENGINE = InnoDB COMMENT = '会话槽位状态（缺则只追问不检索）';

-- -------------------------------------------------------------
-- 五、交易域
-- 写操作只由 Java 执行，Agent 只能建议（needConfirm）
-- -------------------------------------------------------------
CREATE TABLE sp_cart_item (
    id         BIGINT   NOT NULL AUTO_INCREMENT,
    user_id    BIGINT   NOT NULL,
    product_id BIGINT   NOT NULL,
    sku_id     BIGINT            DEFAULT NULL,
    quantity   INT      NOT NULL DEFAULT 1,
    selected   TINYINT(1) NOT NULL DEFAULT 1,
    source     VARCHAR(32) NOT NULL DEFAULT 'user' COMMENT 'user 手动 / agent 代办',
    session_id BIGINT            DEFAULT NULL COMMENT '由哪次对话加购（归因）',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_user (user_id)
) ENGINE = InnoDB COMMENT = '购物车';

CREATE TABLE sp_order (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    order_no     VARCHAR(64)   NOT NULL COMMENT '订单号',
    user_id      BIGINT        NOT NULL,
    total_amount DECIMAL(10, 2) NOT NULL COMMENT '商品总额',
    pay_amount   DECIMAL(10, 2) NOT NULL COMMENT '实付',
    status       TINYINT       NOT NULL DEFAULT 0 COMMENT '0待付 1已付 2已发货 3已完成 4已取消',
    source       VARCHAR(32)   NOT NULL DEFAULT 'web' COMMENT 'web / agent（AI 导购成单归因）',
    session_id   BIGINT                 DEFAULT NULL COMMENT '哪次对话促成',
    created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    paid_at      DATETIME               DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_user (user_id, created_at)
) ENGINE = InnoDB COMMENT = '订单';

-- 冗余商品标题/规格：商品改名或下架不影响历史订单（电商标准做法）
CREATE TABLE sp_order_item (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    order_id      BIGINT        NOT NULL,
    product_id    BIGINT        NOT NULL,
    sku_id        BIGINT                 DEFAULT NULL,
    product_title VARCHAR(200)  NOT NULL COMMENT '下单时快照',
    sku_spec      JSON                   DEFAULT NULL COMMENT '下单时快照',
    price         DECIMAL(10, 2) NOT NULL,
    quantity      INT           NOT NULL,
    amount        DECIMAL(10, 2) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_order (order_id),
    KEY idx_product (product_id)
) ENGINE = InnoDB COMMENT = '订单明细（支持「再来一单」查历史）';

SET FOREIGN_KEY_CHECKS = 1;
