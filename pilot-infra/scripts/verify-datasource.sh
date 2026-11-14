#!/usr/bin/env bash
# =============================================================
# 验证 DataGrip 数据源配置是否可用
#
# 用与 .idea/dataSources.xml 完全相同的 JDBC URL 和 DataGrip 自带的
# mysql-connector-j 驱动跑一次连接，等价于 DataGrip 里的 "Test Connection"。
#
# 用法：
#   bash pilot-infra/scripts/verify-datasource.sh            # 默认 root/1234
#   bash pilot-infra/scripts/verify-datasource.sh root 1234
# =============================================================
set -uo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
USER_NAME="${1:-root}"
PASSWORD="${2:-1234}"

# DataGrip 的驱动缓存位置（随版本变化，这里做兼容查找）
DG_HOME="$APPDATA/JetBrains/DataGrip2026.1"
JAR="$(find "$DG_HOME/jdbc-drivers/MySQL ConnectorJ" -name "mysql-connector-j-*.jar" 2>/dev/null | sort -r | head -1)"

if [ -z "${JAR:-}" ]; then
    echo "[FAIL] 找不到 DataGrip 的 MySQL 驱动 jar" >&2
    echo "       查找路径: $DG_HOME/jdbc-drivers/MySQL Connector J" >&2
    echo "       请先在 DataGrip 里随便连一次 MySQL，让它下载驱动" >&2
    exit 1
fi

echo "[..] 驱动: $JAR"

WORK_DIR="$PROJECT_ROOT/.runtime"
SRC="$WORK_DIR/VerifyDataSource.java"
if [ ! -f "$SRC" ]; then
    echo "[FAIL] 缺少 $SRC" >&2
    exit 1
fi

cd "$WORK_DIR" || exit 1
javac -encoding UTF-8 VerifyDataSource.java 2>&1 | head -10

# stdout.encoding=UTF-8 是必须的：Windows 终端默认 GBK，
# 不加这个参数中文会全部显示成问号
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 \
     -cp ".;$JAR" VerifyDataSource "$USER_NAME" "$PASSWORD"
