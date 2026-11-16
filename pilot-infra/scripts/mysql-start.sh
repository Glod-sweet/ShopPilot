#!/usr/bin/env bash
# =============================================================
# ShopPilot MySQL 启动脚本（无需管理员权限、无需 Docker）
#
# 为什么需要这个脚本：
#   1) 本机 MySQL 的 Windows 服务处于 Stopped，当前用户无管理员权限无法启动
#      （Start-Service 报 Cannot open MySQL service）。
#   2) 直接跑 mysqld 会因主机名是中文，默认 PID 文件名被编码成
#      data\??????_.pid 而报 Permission denied 后 abort。
#   3) 因此显式指定 ASCII 的 --pid-file / --log-error 路径来规避。
#
# 用法（start 是前台阻塞模式，Ctrl+C 即停止，这是最可靠的方式）：
#   bash pilot-infra/scripts/mysql-start.sh start     # 前台运行（推荐）
#   bash pilot-infra/scripts/mysql-start.sh status    # 查看状态
#   bash pilot-infra/scripts/mysql-start.sh stop      # 停止
#   bash pilot-infra/scripts/mysql-start.sh check     # 连库自检
#
# 若希望后台常驻，请在单独的终端窗口运行 start，不要用 nohup/setsid。
# =============================================================
set -uo pipefail

# -------------------------------------------------------------
# 环境加固：确保能调用 Windows 原生命令
#
# 为什么需要：Git Bash 的 netstat/tasklist 实际是调用
# C:\Windows\System32\*.exe。如果启动终端时 PATH 不完整
# （IDE 启动的终端、或 env -i 这类干净环境），会报
# "netstat: command not found"，导致脚本误判成「端口无监听」——
# 这个假阴性比真故障更难查，所以这里直接把 System32 补进 PATH。
# -------------------------------------------------------------
export PATH="/c/Windows/System32:/c/Windows:$PATH"

# 命令解析：优先用绝对路径，彻底不依赖 PATH
NETSTAT="/c/Windows/System32/netstat.exe"
TASKLIST="/c/Windows/System32/tasklist.exe"
[ -x "$NETSTAT" ] || NETSTAT="netstat"
[ -x "$TASKLIST" ] || TASKLIST="tasklist"

MYSQL_HOME="${MYSQL_HOME:-D:/develop/mysql-9.7.0-winx64}"
MYSQLD="$MYSQL_HOME/bin/mysqld.exe"
MYSQL="$MYSQL_HOME/bin/mysql.exe"
DATA_DIR="$MYSQL_HOME/data"
PORT="${MYSQL_PORT:-3306}"
DB_NAME="${MYSQL_DATABASE:-shop_pilot}"
DB_USER="${MYSQL_USER:-root}"
DB_PASS="${MYSQL_PASSWORD:-1234}"

# 运行时文件放项目内，路径全 ASCII，避开中文主机名导致的 PID 文件名乱码
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RUNTIME_DIR="$PROJECT_ROOT/.runtime/mysql"
PID_FILE="$RUNTIME_DIR/mysqld.pid"
LOG_FILE="$RUNTIME_DIR/error.log"

mkdir -p "$RUNTIME_DIR"

port_busy() {
    "$NETSTAT" -ano 2>/dev/null | grep -E "[:.]${PORT}[[:space:]]+.*LISTENING" >/dev/null 2>&1
}

case "${1:-start}" in
  status)
    if port_busy; then
        echo "[OK] MySQL 正在监听 ${PORT} 端口"
        "$NETSTAT" -ano | grep -E "[:.]${PORT}[[:space:]]+.*LISTENING" | head -3
    else
        echo "[DOWN] ${PORT} 端口无监听"
        exit 1
    fi
    ;;

  check)
    if ! port_busy; then
        echo "[FAIL] ${PORT} 端口无监听，请先执行 start" >&2
        exit 1
    fi
    "$MYSQL" -h 127.0.0.1 -P "$PORT" -u "$DB_USER" -p"$DB_PASS" \
        -D "$DB_NAME" --default-character-set=utf8mb4 \
        -e "SELECT COUNT(*) AS product_cnt FROM sp_product;" 2>&1 | grep -v "Warning"
    ;;

  stop)
    killed=0
    if [ -f "$PID_FILE" ]; then
        pid="$(cat "$PID_FILE" 2>/dev/null | tr -d '[:space:]')"
        if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
            kill "$pid" 2>/dev/null && echo "[OK] 已停止 mysqld (pid=$pid)"
            sleep 2
            kill -9 "$pid" 2>/dev/null || true
            killed=1
        fi
        rm -f "$PID_FILE"
    fi

    # pid 文件不可信时（进程被外部杀掉/pid 文件残留），按端口兜底
    if [ "$killed" -eq 0 ]; then
        pid="$("$NETSTAT" -ano 2>/dev/null | grep -E "[:.]${PORT}[[:space:]]+.*LISTENING" | head -1 | awk '{print $NF}' | tr -d '[:space:]')"
        if [ -n "${pid:-}" ]; then
            # taskkill 同样用绝对路径：Git Bash 下 //PID 的双斜杠是防路径转换，不能省
            TASKKILL="/c/Windows/System32/taskkill.exe"
            [ -x "$TASKKILL" ] || TASKKILL="taskkill"
            "$TASKKILL" //PID "$pid" //F 2>/dev/null && echo "[OK] 已按端口停止 pid=$pid"
        else
            echo "[INFO] 没有运行中的实例"
        fi
    fi
    ;;

  start)
    if port_busy; then
        echo "[SKIP] ${PORT} 端口已被占用，MySQL 应该已在运行"
        echo "       如需重启请先执行: $0 stop"
        exit 0
    fi
    if [ ! -x "$MYSQLD" ]; then
        echo "[FAIL] 找不到 mysqld：$MYSQLD" >&2
        echo "       可用 MYSQL_HOME 环境变量指定 MySQL 安装目录" >&2
        exit 1
    fi

    echo "==============================================="
    echo " ShopPilot MySQL"
    echo "   datadir : $DATA_DIR"
    echo "   port    : $PORT"
    echo "   pid-file: $PID_FILE"
    echo "   log     : $LOG_FILE"
    echo "   数据库  : $DB_NAME"
    echo "==============================================="
    echo " 前台运行中，按 Ctrl+C 停止"
    echo ""

    # --pid-file 必须显式指定：默认文件名取主机名，中文主机名会被编码成 ??????_ 导致 Permission denied
    # 前台阻塞运行：进程由当前终端持有，Ctrl+C 干净退出，避免被父进程回收
    exec "$MYSQLD" \
        --datadir="$DATA_DIR" \
        --port="$PORT" \
        --pid-file="$PID_FILE" \
        --log-error="$LOG_FILE" \
        --console
    ;;

  *)
    echo "用法: $0 {start|stop|status|check}" >&2
    exit 2
    ;;
esac
