#!/usr/bin/env bash
# Plan 2 Task M2d：embedding 服务一键部署脚本。
#
# 前提：
#   1. ~/.ssh/config 配好 ssh 别名 old / new（key auth），ssh old / ssh new 直接登
#   2. old 上 /etc/sanyan/embedding-token.env 已创建（chmod 600）+ 同一 token 也写入 new
#   3. old 上 systemd unit /etc/systemd/system/sanyan-embedding.service 已就位
#   4. old 上目录 /opt/sanyan-embedding/ + /var/cache/fastembed/ + /var/log/sanyan-embedding/ 已建
#
# 流程：
#   1. mvn package 打包 embedding-bootstrap
#   2. scp jar 到 old:/opt/sanyan-embedding/
#   3. systemctl restart sanyan-embedding
#   4. polling /actuator/health 等模型加载完成（首次启动 30-120 秒；BGE-M3 ~2.3GB）
#   5. 从 new 经公网调用 old:8080/embed 做端到端验证

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

JAR_NAME="sanyan-embedding-0.1.0.jar"
LOCAL_JAR="embedding-bootstrap/target/${JAR_NAME}"
REMOTE_JAR="/opt/sanyan-embedding/sanyan-embedding.jar"

echo "=== 1. 打包 ==="
mvn clean package -pl embedding-bootstrap -am -DskipTests

echo "=== 2. 上传 jar 到 old ==="
ls -lh "$LOCAL_JAR"
scp "$LOCAL_JAR" "old:${REMOTE_JAR}"

echo "=== 3. 重启 systemd unit ==="
ssh old 'systemctl restart sanyan-embedding'

echo "=== 4. 等待 ready（最多 3 分钟，首次启动会下载 ~2.3GB 模型）==="
ready=false
for i in $(seq 1 18); do
    if ssh old 'curl -fs http://localhost:8080/actuator/health' 2>/dev/null | grep -q '"status":"UP"'; then
        echo "  Ready!"
        ready=true
        break
    fi
    echo "  尝试 $i/18 ... 还在加载模型（等 10s）"
    sleep 10
done

if [ "$ready" = false ]; then
    echo "ERROR: 3 分钟后仍未 ready，请 ssh old 'tail -100 /var/log/sanyan-embedding/server.log' 排查"
    exit 1
fi

echo "=== 5. 端到端验证（new → old:8080/embed）==="
TOKEN=$(ssh new 'cat /etc/sanyan/embedding-token.env | cut -d= -f2')
ssh new "curl -sS -H 'X-Internal-Token: ${TOKEN}' -H 'Content-Type: application/json' \
    -d '{\"texts\":[\"hello\"]}' \
    http://154.8.162.83:8080/embed | head -c 200"

echo
echo
echo "=== Deploy 完成 ==="
