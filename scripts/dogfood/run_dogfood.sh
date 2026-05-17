#!/usr/bin/env bash
# run_dogfood.sh —— 本地 wrapper：把 dogfood_test.py scp 到 new 上跑，运行结束清理临时文件。
#
# 用法：
#   ./run_dogfood.sh                       # 等价 --scenario=all
#   ./run_dogfood.sh --scenario=profile
#   ./run_dogfood.sh --scenario=throttle -v
#   ./run_dogfood.sh --no-clean --scenario=summary
#
# 依赖（你本机）：
#   - ssh new 已配置好（~/.ssh/config 别名 new -> 49.233.213.109）
#   - new 上 Python 3 + websockets + PyJWT 已装（见 README 自检方法）
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOCAL_SCRIPT="$SCRIPT_DIR/dogfood_test.py"
REMOTE_SCRIPT="/tmp/dogfood_test.py"
SERVER="new"

if [[ ! -f "$LOCAL_SCRIPT" ]]; then
    echo "找不到 $LOCAL_SCRIPT" >&2
    exit 1
fi

echo "==> 上传 dogfood_test.py 到 $SERVER:$REMOTE_SCRIPT"
scp -q "$LOCAL_SCRIPT" "$SERVER:$REMOTE_SCRIPT"

cleanup() {
    ssh "$SERVER" "rm -f $REMOTE_SCRIPT" 2>/dev/null || true
}
trap cleanup EXIT

echo "==> ssh $SERVER 跑 dogfood_test.py $*"

# 注：原 SANYAN_OLD_SSH + ssh-agent forwarding 是给 degradation 场景用的（去 old 上 stop sanyan-embedding 服务）。
# 2026-05-17 embedding 改用硅基流动 API 后 degradation 场景已删除，本脚本不再需要 agent forwarding。

# ssh -tt 强制分配 pty——让远端 python stdout 走 pty（行 flush）而不是 pipe（block buffer，
# ssh client 默认要等远端进程退出才一次性 flush）。配合 python3 -u 双层保险，实现实时输出。
exit_code=0
ssh -tt "$SERVER" "python3 -u $REMOTE_SCRIPT $*" || exit_code=$?

exit "$exit_code"
