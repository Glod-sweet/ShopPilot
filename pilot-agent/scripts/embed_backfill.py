"""商品向量回填脚本。

用途：把 sp_product_embedding.content 里的文本调 embedding 模型转成向量，写回
embedding / dim / model / content_hash 四列。没有这一步，混合检索的语义相似度
只能用「2 字滑窗 + 关键词覆盖率」近似，用户说「通勤地铁太吵」时召回不到降噪耳机。

用法：
    # 回填全部未生成向量的商品
    .venv/Scripts/python.exe scripts/embed_backfill.py

    # 强制重建（忽略 content_hash，全部重新向量化）
    .venv/Scripts/python.exe scripts/embed_backfill.py --force

    # 只处理指定商品
    .venv/Scripts/python.exe scripts/embed_backfill.py --ids 100234,100235

    # 只看要做什么，不写库
    .venv/Scripts/python.exe scripts/embed_backfill.py --dry-run

设计要点：
1. **增量**：content 的 MD5 与库里 content_hash 相同就跳过。商品改名后重跑只处理变了的，
   32 个商品的量级看不出差别，但几万商品时这是必须的。
2. **批量**：embedding 接口一次最多 10 条（百炼限制），按批提交而不是逐条，
   避免 32 次网络往返。
3. **向量存 JSON 数组**：MySQL 没有 vector 类型，存 JSON 后由应用层解析算余弦。
   这是 handbook 里记录的取舍——真上向量索引要切 pgvector。
4. **失败不中断**：单批失败重试两次，仍失败就跳过该批并记录，最后汇总报告。
   回填是幂等操作，跑第二遍就能补上漏的。
"""

from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
import sys
from pathlib import Path

# 允许直接以脚本方式运行（python scripts/embed_backfill.py）
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import pymysql
from langchain_openai import OpenAIEmbeddings

from app.config import get_settings

# 百炼 embedding 接口单次最多 10 条，超过会 400
BATCH_SIZE = 10
# 单批失败重试次数
MAX_RETRY = 2


def db_connect() -> pymysql.connections.Connection:
    """连 MySQL。连接参数与 pilot-server 的 application.yml 保持同一套环境变量。"""
    import os

    return pymysql.connect(
        host=os.getenv("MYSQL_HOST", "127.0.0.1"),
        port=int(os.getenv("MYSQL_PORT", "3306")),
        user=os.getenv("MYSQL_USER", "root"),
        password=os.getenv("MYSQL_PASSWORD", "1234"),
        database=os.getenv("MYSQL_DATABASE", "shop_pilot"),
        charset="utf8mb4",
        autocommit=False,
    )


def md5(text: str) -> str:
    return hashlib.md5(text.encode("utf-8")).hexdigest()


def load_targets(conn, force: bool, only_ids: list[int] | None) -> list[tuple[int, str, str]]:
    """挑出需要向量化的商品，返回 [(product_id, content, content_hash)]。

    增量逻辑放在 SQL 里判断，避免把几万行 content 全拉到内存。
    """
    sql = [
        "SELECT product_id, content, content_hash, dim FROM sp_product_embedding",
        "WHERE content IS NOT NULL AND content <> ''",
    ]
    params: list = []

    if only_ids:
        placeholders = ",".join(["%s"] * len(only_ids))
        sql.append(f"AND product_id IN ({placeholders})")
        params.extend(only_ids)

    with conn.cursor() as cur:
        cur.execute(" ".join(sql), params)
        rows = cur.fetchall()

    targets: list[tuple[int, str, str]] = []
    for product_id, content, old_hash, dim in rows:
        new_hash = md5(content)
        if force:
            targets.append((product_id, content, new_hash))
            continue
        # 已有向量且文本没变 → 跳过
        if dim and dim > 0 and old_hash == new_hash:
            continue
        targets.append((product_id, content, new_hash))
    return targets


async def embed_batch(emb: OpenAIEmbeddings, texts: list[str]) -> list[list[float]]:
    """向量化一批文本，失败重试。全部失败则抛异常由调用方处理。"""
    last_error: Exception | None = None
    for attempt in range(MAX_RETRY + 1):
        try:
            vectors = await emb.aembed_documents(texts)
            if len(vectors) != len(texts):
                raise RuntimeError(f"返回条数不匹配：期望 {len(texts)}，实际 {len(vectors)}")
            return vectors
        except Exception as exc:  # noqa: BLE001
            last_error = exc
            if attempt < MAX_RETRY:
                wait = 1.5 * (attempt + 1)
                print(f"    重试 {attempt + 1}/{MAX_RETRY}（{wait}s 后）：{exc}")
                await asyncio.sleep(wait)
    raise RuntimeError(f"向量化失败：{last_error}")


def save_vectors(conn, items: list[tuple[int, str, str, list[float]]], model: str) -> int:
    """写回向量。embedding 列是 JSON，dim 记录实际维度，content_hash 用于下次增量判断。"""
    if not items:
        return 0

    with conn.cursor() as cur:
        for product_id, _content, content_hash, vec in items:
            cur.execute(
                """
                UPDATE sp_product_embedding
                SET embedding = %s,
                    dim = %s,
                    model = %s,
                    content_hash = %s
                WHERE product_id = %s
                """,
                (json.dumps(vec, separators=(",", ":")), len(vec), model, content_hash, product_id),
            )
    conn.commit()
    return len(items)


async def main() -> int:
    parser = argparse.ArgumentParser(description="回填商品向量")
    parser.add_argument("--force", action="store_true", help="忽略 content_hash，全部重新向量化")
    parser.add_argument("--ids", type=str, default="", help="只处理指定商品，逗号分隔")
    parser.add_argument("--dry-run", action="store_true", help="只显示计划，不写库")
    args = parser.parse_args()

    only_ids = [int(x) for x in args.ids.split(",") if x.strip()] if args.ids else None

    s = get_settings()
    if not s.llm_api_key:
        print("错误：LLM_API_KEY 为空，无法调用 embedding 接口。请在 pilot-agent/.env 里配置。")
        return 1

    conn = db_connect()
    try:
        with conn.cursor() as cur:
            cur.execute("SELECT COUNT(*) FROM sp_product_embedding")
            total = cur.fetchone()[0]
            cur.execute("SELECT COUNT(*) FROM sp_product_embedding WHERE dim > 0")
            already = cur.fetchone()[0]

        targets = load_targets(conn, args.force, only_ids)

        print("=== 向量回填 ===")
        print(f"  商品总数    : {total}")
        print(f"  已有向量    : {already}")
        print(f"  本次待处理  : {len(targets)}")
        print(f"  模型        : {s.embedding_model} ({s.embedding_dim} 维)")
        print(f"  批量大小    : {BATCH_SIZE}")
        if args.force:
            print("  模式        : 强制重建")
        if args.dry_run:
            print("  模式        : DRY RUN（不写库）")

        if not targets:
            print("\n没有需要处理的商品。")
            return 0

        if args.dry_run:
            for pid, content, _h in targets[:10]:
                print(f"    {pid}: {content[:50]}…")
            if len(targets) > 10:
                print(f"    … 另有 {len(targets) - 10} 条")
            return 0

        emb = OpenAIEmbeddings(
            model=s.embedding_model,
            api_key=s.llm_api_key,
            base_url=s.llm_base_url,
            dimensions=s.embedding_dim,
            # 阿里兼容端点的 input 不接受 token 数组，必须关掉预分词
            check_embedding_ctx_length=False,
        )

        done = 0
        failed: list[int] = []
        print()

        for i in range(0, len(targets), BATCH_SIZE):
            batch = targets[i : i + BATCH_SIZE]
            ids = [t[0] for t in batch]
            texts = [t[1] for t in batch]
            print(f"  批次 {i // BATCH_SIZE + 1}: {len(batch)} 条（{ids[0]}…{ids[-1]}）")

            try:
                vectors = await embed_batch(emb, texts)
            except Exception as exc:  # noqa: BLE001
                print(f"    [FAIL] {exc}")
                failed.extend(ids)
                continue

            items = [
                (batch[j][0], batch[j][1], batch[j][2], vectors[j]) for j in range(len(batch))
            ]
            n = save_vectors(conn, items, s.embedding_model)
            done += n
            dim = len(vectors[0]) if vectors else 0
            print(f"    [OK] 写入 {n} 条，维度 {dim}")

        print("\n=== 结果 ===")
        print(f"  成功写入 : {done}")
        print(f"  失败     : {len(failed)}" + (f" -> {failed}" if failed else ""))

        with conn.cursor() as cur:
            cur.execute("SELECT COUNT(*) FROM sp_product_embedding WHERE dim > 0")
            now = cur.fetchone()[0]
        print(f"  库内已有 : {now}/{total}")
        print("\n" + ("PASS: 向量回填完成" if not failed else "PARTIAL: 部分失败，重跑可补齐"))
        return 0 if not failed else 2
    finally:
        conn.close()


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
