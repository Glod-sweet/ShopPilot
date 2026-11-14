"""本地开发启动脚本：uv run python -m app.main 不适用，用 uvicorn 起。"""

import uvicorn

from app.config import get_settings

if __name__ == "__main__":
    s = get_settings()
    uvicorn.run("app.main:app", host=s.host, port=s.port, reload=True)
