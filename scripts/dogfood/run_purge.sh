#!/usr/bin/env bash
# run_purge.sh —— 本机 wrapper：把 purge_user.py + dogfood_test.py scp 到 new 上，
# 清空指定用户的全部业务数据（保留 users 账号行），运行结束清理临时文件。
#
# 用法：
#   ./run_purge.sh 1            # 清空 user_id=1 的全部数据
#   ./run_purge.sh 1 2 3        # 批量
#
# ⚠️ 直接删，无 dry-run / 二次确认。删的是「用户的数据」，不是删用户账号本身。
#
# 依赖：ssh new 已配置好（~/.ssh/config 别名 new）；new 上有 python3 + psql + redis-cli。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER="new"

if [[ $# -eq 0 ]]; then
    echo "用法: ./run_purge.sh <user_id> [<user_id> ...]" >&2
    exit 2
fi

echo "==> 上传 purge_user.py + dogfood_test.py 到 $SERVER:/tmp"
scp -q "$SCRIPT_DIR/purge_user.py" "$SCRIPT_DIR/dogfood_test.py" "$SERVER:/tmp/"

cleanup() {
    ssh "$SERVER" "rm -f /tmp/purge_user.py /tmp/dogfood_test.py" 2>/dev/null || true
}
trap cleanup EXIT

echo "==> ssh $SERVER 跑 purge_user.py $*"
ssh -tt "$SERVER" "python3 -u /tmp/purge_user.py $*"
