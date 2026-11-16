"""RAG 检索。

为什么导购需要 RAG：
商品的向量检索（Java 侧 pgvector）解决"找出符合条件的具体商品"，
但用户问「露营第一次买什么」「300 送女生会不会太便宜」这类问题时，
要的不是商品，是**导购常识**。这部分知识在文档里，不在商品库里。

所以本项目是双 RAG：
- 商品检索 → 工具（search_products，走 Java）
- 知识检索 → 本模块（本地向量库，走内存）

为什么不用 pgvector 存知识：
知识库是静态的、几十 KB、随代码发布，没必要为它引入一个需要运维的数据库。
进程内 numpy 检索在千级 chunk 规模下是微秒级，完全够用。
真到了需要持久化的量级再迁移，接口不用改。

检索策略：
向量召回（语义） + 关键词加权（术语），做加权融合。
纯向量在中文电商场景会漏掉精确词——用户问"IPX5"，向量检索可能返回"防水性能好"的段落，
但用户要的就是 IPX5 那一条。所以关键词命中要额外加分。

降级：embedding 接口不可用时用字符 n-gram 哈希向量兜底，
保证功能不中断（效果会差，但比 500 好）。
"""

from __future__ import annotations

import hashlib
import logging
import math
import re
from dataclasses import dataclass
from pathlib import Path

import numpy as np

from app.config import get_settings

logger = logging.getLogger("pilot.rag")

KNOWLEDGE_DIR = Path(__file__).resolve().parent.parent / "knowledge"


@dataclass
class Chunk:
    text: str
    source: str
    heading: str
    vector: np.ndarray | None = None


def split_markdown(text: str, *, source: str, max_chars: int = 420) -> list[Chunk]:
    """按标题切分，再按长度二次切分。

    为什么先按标题：Markdown 的标题就是天然的语义边界，
    强行定长切分会把"预算怎么分配"和"常被忽略的点"切到一块，检索出来答非所问。
    """
    chunks: list[Chunk] = []
    current_heading = ""
    buffer: list[str] = []

    def flush() -> None:
        if not buffer:
            return
        body = "\n".join(buffer).strip()
        if not body:
            return
        # 超长段落再按句号切，保留标题作为上下文前缀
        if len(body) <= max_chars:
            prefix = f"【{current_heading}】" if current_heading else ""
            chunks.append(Chunk(text=prefix + body, source=source, heading=current_heading))
        else:
            sentences = re.split(r"(?<=[。！？\n])", body)
            buf = ""
            for sent in sentences:
                if len(buf) + len(sent) > max_chars and buf:
                    prefix = f"【{current_heading}】" if current_heading else ""
                    chunks.append(Chunk(text=prefix + buf.strip(), source=source, heading=current_heading))
                    buf = sent
                else:
                    buf += sent
            if buf.strip():
                prefix = f"【{current_heading}】" if current_heading else ""
                chunks.append(Chunk(text=prefix + buf.strip(), source=source, heading=current_heading))

    for line in text.splitlines():
        if line.startswith("#"):
            flush()
            buffer = []
            current_heading = line.lstrip("#").strip()
        else:
            buffer.append(line)
    flush()
    return chunks


def _hash_vector(text: str, dim: int) -> np.ndarray:
    """字符 n-gram 哈希向量。仅作 embedding 不可用时的兜底。"""
    vec = np.zeros(dim, dtype=np.float32)
    clean = re.sub(r"\s+", "", text)
    for n in (1, 2, 3):
        for i in range(len(clean) - n + 1):
            gram = clean[i : i + n]
            h = int(hashlib.md5(gram.encode()).hexdigest()[:8], 16)
            vec[h % dim] += 1.0
    norm = np.linalg.norm(vec)
    return vec / norm if norm else vec


def _tokenize(text: str) -> set[str]:
    """粗粒度分词：中文取 2-gram，英文/数字取整词。够用于关键词加权。"""
    tokens: set[str] = set()
    for w in re.findall(r"[a-zA-Z0-9]+", text.lower()):
        if len(w) >= 2:
            tokens.add(w)
    clean = re.sub(r"[^\u4e00-\u9fa5a-zA-Z0-9]", "", text)
    for i in range(len(clean) - 1):
        tokens.add(clean[i : i + 2].lower())
    return tokens


class KnowledgeBase:
    def __init__(self) -> None:
        self._chunks: list[Chunk] = []
        self._df: dict[str, int] = {}
        self._loaded = False
        self.degraded = False

    @property
    def size(self) -> int:
        return len(self._chunks)

    async def aload(self, *, force: bool = False) -> None:
        if self._loaded and not force:
            return
        docs: list[Chunk] = []
        if KNOWLEDGE_DIR.exists():
            for path in sorted(KNOWLEDGE_DIR.glob("*.md")):
                docs.extend(split_markdown(path.read_text(encoding="utf-8"), source=path.name))
        else:
            logger.warning("rag: 知识目录不存在 %s", KNOWLEDGE_DIR)

        if not docs:
            self._chunks = []
            self._loaded = True
            return

        # 先建词表（用于 IDF），再算向量
        for c in docs:
            for t in _tokenize(c.text):
                self._df[t] = self._df.get(t, 0) + 1

        dim = get_settings().embedding_dim
        try:
            from app.llm import get_embeddings

            emb = get_embeddings()
            vectors = await emb.aembed_documents([c.text for c in docs])
            for c, v in zip(docs, vectors, strict=True):
                arr = np.asarray(v, dtype=np.float32)
                n = np.linalg.norm(arr)
                c.vector = arr / n if n else arr
            self.degraded = False
            logger.info("rag: 向量索引就绪 %d chunks dim=%d", len(docs), len(vectors[0]))
        except Exception as exc:  # noqa: BLE001 - 降级不阻断
            logger.warning("rag: embedding 不可用(%s)，降级为哈希向量", exc)
            for c in docs:
                c.vector = _hash_vector(c.text, dim)
            self.degraded = True

        self._chunks = docs
        self._loaded = True

    def _idf(self, token: str) -> float:
        n = len(self._chunks)
        df = self._df.get(token, 0)
        return math.log((n + 1) / (df + 1)) + 1.0

    async def asearch(self, query: str, *, top_k: int = 3, min_score: float = 0.18) -> list[tuple[Chunk, float]]:
        """混合检索：向量余弦 + 关键词 IDF 加权。

        分数 = 0.7 * cosine + 0.3 * keyword_overlap
        权重偏向向量是因为用户的问法通常很口语，字面重合少；
        但 0.3 的关键词分量足以在"IPX5""LDAC"这类术语查询上把精确段落顶上来。
        """
        await self.aload()
        if not self._chunks or not query.strip():
            return []

        q_tokens = _tokenize(query)
        total_idf = sum(self._idf(t) for t in q_tokens) or 1.0

        # 查询向量
        if self.degraded:
            q_vec = _hash_vector(query, get_settings().embedding_dim)
        else:
            try:
                from app.llm import get_embeddings

                q_vec = np.asarray(await get_embeddings().aembed_query(query), dtype=np.float32)
                n = np.linalg.norm(q_vec)
                q_vec = q_vec / n if n else q_vec
            except Exception as exc:  # noqa: BLE001
                logger.warning("rag: 查询向量化失败(%s)，本次用哈希向量", exc)
                q_vec = _hash_vector(query, get_settings().embedding_dim)

        scored: list[tuple[Chunk, float]] = []
        for c in self._chunks:
            if c.vector is None:
                continue
            cosine = float(np.dot(q_vec, c.vector))
            c_tokens = _tokenize(c.text)
            hit = sum(self._idf(t) for t in q_tokens & c_tokens)
            keyword = hit / total_idf
            score = 0.7 * cosine + 0.3 * keyword
            if score >= min_score:
                scored.append((c, score))

        scored.sort(key=lambda x: x[1], reverse=True)
        return scored[:top_k]

    async def aformat(self, query: str, *, top_k: int = 3) -> str:
        """检索并拼成提示词可用的片段。无命中返回空串，不要塞"未找到"占位。"""
        hits = await self.asearch(query, top_k=top_k)
        if not hits:
            return ""
        blocks = []
        for chunk, score in hits:
            blocks.append(f"[{chunk.source} · {chunk.heading}]\n{chunk.text}")
        return "\n\n".join(blocks)


knowledge_base = KnowledgeBase()
