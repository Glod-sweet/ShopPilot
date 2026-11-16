"""RAG 切分与检索测试。

测两件事：
1. 切分策略 —— 按标题切而不是定长切，这是检索质量的前提。
   定长切分会把「预算怎么分配」和「常被忽略的点」切到一起，检索出来答非所问。
2. 检索打分 —— 向量与关键词的加权融合是否真的能把精确词顶上来。

不测 embedding 接口本身（那是外部依赖），只测本地逻辑。
"""

from __future__ import annotations

import numpy as np

from app.rag import Chunk, _hash_vector, _tokenize, split_markdown


class TestSplitMarkdown:
    def test_split_by_heading(self) -> None:
        text = """# 标题一

内容一。

# 标题二

内容二。
"""
        chunks = split_markdown(text, source="test.md")
        assert len(chunks) == 2
        assert chunks[0].heading == "标题一"
        assert chunks[1].heading == "标题二"

    def test_heading_used_as_prefix(self) -> None:
        """标题要拼进正文前缀，否则切出来的块丢了上下文"""
        text = "# 预算怎么分配\n\n200 以内够用。"
        chunks = split_markdown(text, source="test.md")
        assert chunks[0].text.startswith("【预算怎么分配】")

    def test_long_section_split_by_sentence(self) -> None:
        """超长段落要按句号二次切分，且每块都带标题前缀"""
        body = "。".join([f"这是第{i}句话，内容足够长用来测试切分逻辑" for i in range(20)])
        text = f"# 长段落\n\n{body}。"
        chunks = split_markdown(text, source="test.md", max_chars=100)

        assert len(chunks) > 1, "超长段落应该被切开"
        for c in chunks:
            assert len(c.text) <= 200, f"切出来的块太长: {len(c.text)}"
            assert c.heading == "长段落"
            assert c.text.startswith("【长段落】")

    def test_empty_input(self) -> None:
        assert split_markdown("", source="test.md") == []

    def test_only_heading(self) -> None:
        chunks = split_markdown("# 只有标题", source="test.md")
        assert chunks == [], "只有标题没有正文时不该产出空块"

    def test_multiple_headings_preserve_order(self) -> None:
        text = "# A\n\n内容A\n\n# B\n\n内容B\n\n# C\n\n内容C"
        chunks = split_markdown(text, source="test.md")
        assert [c.heading for c in chunks] == ["A", "B", "C"]

    def test_source_recorded(self) -> None:
        """source 要记录下来，检索结果里要能标注出处"""
        chunks = split_markdown("# 标题\n\n内容", source="guide.md")
        assert chunks[0].source == "guide.md"


class TestTokenize:
    def test_chinese_bigram(self) -> None:
        tokens = _tokenize("蓝牙耳机")
        assert "蓝牙" in tokens
        assert "牙耳" in tokens
        assert "耳机" in tokens

    def test_english_word(self) -> None:
        tokens = _tokenize("IPX5 防水")
        assert "ipx5" in tokens

    def test_removes_punctuation(self) -> None:
        tokens = _tokenize("你好，世界！")
        assert all("，" not in t and "！" not in t for t in tokens)

    def test_empty(self) -> None:
        assert _tokenize("") == set()


class TestHashVector:
    def test_deterministic(self) -> None:
        """同样输入必须产出同样向量，否则索引和查询不在同一空间"""
        v1 = _hash_vector("测试文本", 128)
        v2 = _hash_vector("测试文本", 128)
        assert np.array_equal(v1, v2)

    def test_normalized(self) -> None:
        v = _hash_vector("测试文本", 128)
        assert abs(np.linalg.norm(v) - 1.0) < 1e-5

    def test_different_text_different_vector(self) -> None:
        v1 = _hash_vector("蓝牙耳机", 128)
        v2 = _hash_vector("露营帐篷", 128)
        assert not np.array_equal(v1, v2)

    def test_correct_dim(self) -> None:
        assert len(_hash_vector("文本", 256)) == 256

    def test_empty_text(self) -> None:
        """空文本不该崩，返回零向量"""
        v = _hash_vector("", 64)
        assert len(v) == 64
        assert np.allclose(v, 0)


class TestChunk:
    def test_chunk_dataclass(self) -> None:
        c = Chunk(text="内容", source="a.md", heading="标题")
        assert c.text == "内容"
        assert c.vector is None, "初始向量应为 None，由 aload 填充"


class TestIdfWeighting:
    """IDF 的作用：让罕见词比常见词更有区分度。

    这是关键词打分能压过纯向量的关键 —— 用户问 "IPX5"，
    IPX5 在语料里只出现一次（高 IDF），而「防水」出现十次（低 IDF），
    命中 IPX5 的段落应该被显著提权。
    """

    def test_rare_token_higher_idf(self) -> None:
        from app.rag import KnowledgeBase

        kb = KnowledgeBase()
        # 手工构造：10 个块，「防水」出现在 8 个里，「IPX5」只出现在 1 个里
        kb._chunks = [Chunk(text=f"块{i}", source="t", heading="h") for i in range(10)]
        kb._df = {"防水": 8, "ipx5": 1}

        idf_common = kb._idf("防水")
        idf_rare = kb._idf("ipx5")
        assert idf_rare > idf_common, "罕见词的 IDF 必须更高"

    def test_unknown_token_max_idf(self) -> None:
        from app.rag import KnowledgeBase

        kb = KnowledgeBase()
        kb._chunks = [Chunk(text=f"块{i}", source="t", heading="h") for i in range(10)]
        kb._df = {"防水": 8}
        # 语料里没出现过的词 df=0，IDF 应该高于出现过 8 次的词
        assert kb._idf("从未出现的词") > kb._idf("防水")
