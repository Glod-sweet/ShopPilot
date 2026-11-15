"""应用配置。所有可调参数集中在这里，避免散落在业务代码里。

密钥只从环境变量 / .env 读取，代码里不出现明文。
"""

from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    app_env: str = "dev"
    host: str = "0.0.0.0"
    port: int = 8000

    # ---- LLM：阿里云百炼，OpenAI 兼容协议 ----
    llm_base_url: str = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    llm_api_key: str = ""
    llm_model: str = "qwen-plus"
    llm_temperature: float = 0.3
    llm_max_tokens: int = 1500

    # 多模态识图模型。qwen-plus 不支持图片，图片请求必须切到 VL 模型
    vl_model: str = "qwen3-vl-plus"

    # 向量模型。text-embedding-v2 输出 1536 维
    embedding_model: str = "text-embedding-v2"
    embedding_dim: int = 1536

    # ---- 外部检索：Tavily MCP ----
    tavily_api_key: str = ""
    tavily_mcp_command: str = "npx"
    # 多个参数用逗号分隔。Windows 下 npx 是 .cmd，必须经 cmd /c 启动
    tavily_mcp_args: str = "-y,tavily-mcp@0.2.22"
    mcp_enabled: bool = True
    mcp_timeout_seconds: float = 60.0

    # ---- 会话记忆 ----
    # 连不上则自动降级为进程内存，本地开发不强依赖 Redis
    redis_url: str = "redis://:123456@127.0.0.1:6379/0"
    memory_ttl_seconds: int = 7 * 24 * 3600
    # 超过 memory_max_turns 条触发摘要压缩，只保留最近 memory_keep_turns 条原文
    memory_max_turns: int = 12
    memory_keep_turns: int = 6

    # ---- 上下文工程 ----
    # 单次请求允许携带的历史条数上限，防止上下文无限膨胀
    context_max_history: int = 20
    context_max_chars: int = 6000

    internal_token: str = "dev-internal-token-change-me"
    java_base_url: str = "http://localhost:8080"

    @property
    def mcp_args_list(self) -> list[str]:
        return [a.strip() for a in self.tavily_mcp_args.split(",") if a.strip()]


@lru_cache
def get_settings() -> Settings:
    return Settings()
