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

# 把本机的 SANYAN_OLD_SSH 透传给 new 上的 python 进程。new 上没配 old 别名，所以
# 默认用 IP root@154.8.162.83。配合 ssh -A agent forwarding，让 python 在 new 上
# ssh 到 old 时用本机的 key 鉴权（new 上没 old 的私钥）。
OLD_SSH_FORWARD="${SANYAN_OLD_SSH:-root@154.8.162.83}"

# 启动 ssh-agent 并加载 key 用于 agent forwarding（new 上 ssh 到 old 需要本机 key）
if [[ -z "${SSH_AUTH_SOCK:-}" ]]; then
    echo "==> 启动 ssh-agent"
    eval "$(ssh-agent -s)" >/dev/null
fi
if ! ssh-add -l >/dev/null 2>&1; then
    echo "==> 加载本机 SSH key (id_ed25519 / id_rsa) 到 agent"
    for keyfile in ~/.ssh/id_ed25519 ~/.ssh/id_rsa ~/.ssh/id_ecdsa; do
        [[ -f "$keyfile" ]] && ssh-add "$keyfile" 2>/dev/null || true
    done
fi

# -A: agent forwarding，让 new 上的 ssh 命令能用本机 key 连 old
# 不加 -t 因为我们用 python3 -u 强制 unbuffered 输出，避免 tty 错乱
exit_code=0
ssh -A "$SERVER" "SANYAN_OLD_SSH='$OLD_SSH_FORWARD' python3 -u $REMOTE_SCRIPT $*" || exit_code=$?

exit "$exit_code"
