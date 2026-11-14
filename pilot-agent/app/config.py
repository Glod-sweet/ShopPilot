"""应用配置。所有可调参数集中在这里，避免散落在业务代码里。"""

from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    app_env: str = "dev"
    host: str = "0.0.0.0"
    port: int = 8000

    llm_base_url: str = "https://api.deepseek.com/v1"
    llm_api_key: str = ""
    llm_model: str = "deepseek-chat"
    llm_temperature: float = 0.3

    internal_token: str = "dev-internal-token-change-me"
    java_base_url: str = "http://localhost:8080"


@lru_cache
def get_settings() -> Settings:
    return Settings()
