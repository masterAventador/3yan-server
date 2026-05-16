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

# 任何参数原封透传给 python3 dogfood_test.py
# 用 -t -t 强制分配 pseudo-tty 以保证输出实时 flush
cleanup() {
    ssh "$SERVER" "rm -f $REMOTE_SCRIPT" 2>/dev/null || true
}
trap cleanup EXIT

echo "==> ssh $SERVER 跑 dogfood_test.py $*"

# 把本机的 SANYAN_OLD_SSH 透传给 new 上的 python 进程；本机有 old 别名时通常会传
# 'old'，但 new 上一般没配 old，所以这里默认用 IP，由用户在本机也可以覆盖
OLD_SSH_FORWARD="${SANYAN_OLD_SSH:-}"

# 不加 -t 因为我们用 python3 -u 强制 unbuffered 输出，避免 tty 错乱
exit_code=0
ssh "$SERVER" "SANYAN_OLD_SSH='$OLD_SSH_FORWARD' python3 -u $REMOTE_SCRIPT $*" || exit_code=$?

exit "$exit_code"
