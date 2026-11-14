-- 商品向量表：不改动原有商品表，只新增这一张
-- 执行库：shop_pilot_vector (PostgreSQL 17)

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS product_embedding (
    product_id   BIGINT PRIMARY KEY,
    title        TEXT        NOT NULL,
    content      TEXT        NOT NULL,          -- 标题+卖点+属性拼接后用于向量化
    embedding    vector(1024) NOT NULL,
    category_id  BIGINT,                        -- 冗余，用于过滤
    price        NUMERIC(10, 2),                -- 冗余，用于范围过滤
    brand_id     BIGINT,
    on_shelf     BOOLEAN     NOT NULL DEFAULT TRUE,
    stock        INT         NOT NULL DEFAULT 0,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- HNSW 索引：比 IVFFlat 更适合增量写入，召回也更稳
CREATE INDEX IF NOT EXISTS idx_product_embedding_hnsw
    ON product_embedding USING hnsw (embedding vector_cosine_ops);

-- 过滤字段索引
CREATE INDEX IF NOT EXISTS idx_product_embedding_filter
    ON product_embedding (category_id, price, on_shelf);
