"""模型工厂。

所有模型都走阿里云百炼的 OpenAI 兼容端点，换供应商只需改 config，不动业务代码。

三个模型各司其职：
- chat   : 主力对话 + 工具调用
- vl     : 多模态识图（chat 模型不吃图片）
- embed  : 向量化，供 RAG 用
"""

from functools import lru_cache

from langchain_openai import ChatOpenAI, OpenAIEmbeddings

from app.config import get_settings


@lru_cache
def get_chat_model() -> ChatOpenAI:
    s = get_settings()
    return ChatOpenAI(
        model=s.llm_model,
        api_key=s.llm_api_key,
        base_url=s.llm_base_url,
        temperature=s.llm_temperature,
        max_tokens=s.llm_max_tokens,
        timeout=120,
        max_retries=2,
    )


@lru_cache
def get_vl_model() -> ChatOpenAI:
    """多模态模型。温度调低，识图要的是稳定描述而不是发挥。"""
    s = get_settings()
    return ChatOpenAI(
        model=s.vl_model,
        api_key=s.llm_api_key,
        base_url=s.llm_base_url,
        temperature=0.1,
        max_tokens=800,
        timeout=120,
        max_retries=2,
    )


@lru_cache
def get_embeddings() -> OpenAIEmbeddings:
    s = get_settings()
    return OpenAIEmbeddings(
        model=s.embedding_model,
        api_key=s.llm_api_key,
        base_url=s.llm_base_url,
        dimensions=s.embedding_dim,
        # 阿里兼容端点的 input 不接受 token 数组，必须关掉预分词
        check_embedding_ctx_length=False,
    )
