#!/usr/bin/env bash
# 部署 sanyan-server 到默认服务器（new = 49.233.213.109）
#
# 部署流程：
#   1. 本机 mvn clean package 出 fat jar
#   2. scp jar 到 new:/opt/3yan/3yan-server/target/
#   3. ssh new systemctl restart 3yan-server（用 systemd 接管启停 + 故障自重启）
#   4. 健康检查：端口 LISTEN + systemctl is-active + journalctl 含 'Started SanyanApplication'
#
# 前置（已配，运维一次性，本脚本不动）：
#   - systemd unit /etc/systemd/system/3yan-server.service：
#     · ExecStart 含 -Dspring.profiles.active=prod
#     · EnvironmentFile=/etc/3yan/3yan-server.env
#     · Restart=on-failure
#   - /etc/3yan/3yan-server.env（权限 600）：所有 API key + DB credentials 唯一来源
#     必含：JWT_SECRET / PG_USER / PG_PASSWORD
#           DOUBAO_API_KEY / DEEPSEEK_API_KEY / SILICONFLOW_API_KEY
#           ASR_ACCESS_TOKEN / TTS_ACCESS_TOKEN
#           COS_SECRET_ID / COS_SECRET_KEY / COS_BUCKET / COS_REGION
#     application-prod.yml 用 ${KEY} 严格语法，缺 key 启动 fail-fast。
#
# 用法: ./deploy.sh

set -euo pipefail

SERVER="new"
SERVICE_NAME="3yan-server"
JAR_NAME="sanyan-server-0.1.0.jar"
REMOTE_JAR_PATH="/opt/3yan/3yan-server/target/$JAR_NAME"
HEALTH_PORT=8080

cd "$(dirname "${BASH_SOURCE[0]}")"

echo "==> [1/4] 本地打包..."
mvn clean package -DskipTests -q

LOCAL_JAR="bootstrap/target/$JAR_NAME"
[[ -f "$LOCAL_JAR" ]] || { echo "打包失败：$LOCAL_JAR 不存在" >&2; exit 1; }

echo "==> [2/4] 上传 jar 到 $SERVER..."
scp -q "$LOCAL_JAR" "$SERVER:$REMOTE_JAR_PATH"

echo "==> [3/4] systemctl restart ${SERVICE_NAME} (systemd 接管启停)..."
ssh "$SERVER" "systemctl restart ${SERVICE_NAME}"

echo "==> [4/4] 等待健康检查（最多 60s）..."
# 三重确认：端口 LISTEN + systemd is-active + 日志含 'Started SanyanApplication'
# 防"端口 listen 但 ApplicationContext 启动失败"的误报（Tomcat 在 context 初始化前就 bind 端口）。
DEPLOY_OK=0
for i in $(seq 1 30); do
  PORT_OK="$(ssh "$SERVER" "ss -tln | grep -q ':$HEALTH_PORT '" 2>/dev/null && echo y || echo n)"
  ACTIVE_OK="$(ssh "$SERVER" "systemctl is-active $SERVICE_NAME" 2>/dev/null || echo failed)"
  STARTED_OK="$(ssh "$SERVER" "journalctl -u $SERVICE_NAME --since '30 seconds ago' --no-pager 2>/dev/null | grep -q 'Started SanyanApplication'" 2>/dev/null && echo y || echo n)"

  if [[ "$PORT_OK" == "y" && "$ACTIVE_OK" == "active" && "$STARTED_OK" == "y" ]]; then
    echo "✅ 部署成功 (端口=LISTEN service=active 应用已 Started)"
    DEPLOY_OK=1
    break
  fi
  sleep 2
done

if [[ "$DEPLOY_OK" -ne 1 ]]; then
  echo "❌ 60s 内未通过健康检查 (port=${PORT_OK} active=${ACTIVE_OK} started=${STARTED_OK})" >&2
  echo "请查日志: ssh ${SERVER} 'journalctl -u ${SERVICE_NAME} -n 100 --no-pager'" >&2
  exit 1
fi
