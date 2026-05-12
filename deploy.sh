#!/usr/bin/env bash
# 部署 sanyan-server 到默认服务器（new = 49.233.213.109）
# 前置条件：服务器 /opt/3yan/3yan-server/.env 已配置（JWT_SECRET / DOUBAO_API_KEY / PG_USER / PG_PASSWORD），权限 600
# 用法: ./deploy.sh

set -euo pipefail

SERVER="new"
JAR_NAME="sanyan-server-0.1.0.jar"
HEALTH_PORT=8080

cd "$(dirname "${BASH_SOURCE[0]}")"

echo "==> [1/4] 本地打包..."
mvn clean package -DskipTests -q

LOCAL_JAR="target/$JAR_NAME"
[[ -f "$LOCAL_JAR" ]] || { echo "打包失败：$LOCAL_JAR 不存在" >&2; exit 1; }

echo "==> [2/4] 上传 jar 到 $SERVER..."
scp -q "$LOCAL_JAR" "$SERVER:/opt/3yan/3yan-server/target/$JAR_NAME"

echo "==> [3/4] 重启远程服务..."
ssh "$SERVER" 'bash -s' <<'REMOTE'
set -euo pipefail

REMOTE_DIR="/opt/3yan/3yan-server"
REMOTE_JAR="$REMOTE_DIR/target/sanyan-server-0.1.0.jar"
ENV_FILE="$REMOTE_DIR/.env"
LOG_FILE="$REMOTE_DIR/server.log"

[[ -f "$ENV_FILE" ]] || { echo "缺少 $ENV_FILE，无法启动" >&2; exit 1; }

# 停旧进程（精确匹配 jar 路径，避免误杀其他 java 进程）
OLD_PID="$(pgrep -f "java -jar $REMOTE_JAR" || true)"
if [[ -n "$OLD_PID" ]]; then
  echo "    停止旧进程 PID=$OLD_PID"
  kill "$OLD_PID"
  for _ in $(seq 1 30); do
    if ! kill -0 "$OLD_PID" 2>/dev/null; then break; fi
    sleep 1
  done
  if kill -0 "$OLD_PID" 2>/dev/null; then
    echo "    优雅停止超时，强制 kill -9"
    kill -9 "$OLD_PID"
    sleep 1
  fi
fi

# 轮转日志
[[ -f "$LOG_FILE" ]] && mv "$LOG_FILE" "${LOG_FILE}.prev"

# 启动新进程
cd "$REMOTE_DIR"
set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

nohup java -jar "$REMOTE_JAR" > "$LOG_FILE" 2>&1 &
NEW_PID=$!
disown
echo "    新进程 PID=$NEW_PID，日志: $LOG_FILE"
REMOTE

echo "==> [4/4] 等待健康检查（端口 $HEALTH_PORT LISTEN）..."
for _ in $(seq 1 30); do
  if ssh "$SERVER" "ss -tln | grep -q ':$HEALTH_PORT '" 2>/dev/null; then
    echo "✅ 部署成功（端口 $HEALTH_PORT 已 LISTEN）"
    exit 0
  fi
  sleep 2
done

echo "❌ 60s 内端口 $HEALTH_PORT 未 LISTEN，请查日志: ssh $SERVER 'tail -100 /opt/3yan/3yan-server/server.log'" >&2
exit 1
