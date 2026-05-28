#!/usr/bin/env bash
# run_dogfood.sh —— 本地 wrapper：把 dogfood_test.py scp 到 new 上跑，运行结束清理临时文件。
#
# 用法：
#   ./run_dogfood.sh                        # 等价 --scenario=all（plan2 4 个场景）
#   ./run_dogfood.sh --scenario=profile
#   ./run_dogfood.sh --scenario=throttle -v
#   ./run_dogfood.sh --no-clean --scenario=summary
#   ./run_dogfood.sh --plan3                # 跑 plan3 全部 10 个场景（含阈值缩短 + 回滚）
#   ./run_dogfood.sh --plan3 --scenario=plan3_baseline
#   ./run_dogfood.sh --plan3 --scenario=all-plan3  # 同 --plan3，跑全部 plan3 场景
#   ./run_dogfood.sh --scenario=all-plan3   # 不加 --plan3 也可（--scenario=all-plan3 自动启用 plan3 模式）
#   ./run_dogfood.sh --plan4                # 跑 plan4 全部场景（含 cron/recall 阈值缩短 + 回滚）
#   ./run_dogfood.sh --plan4 --scenario=plan4_c_event_followup
#   ./run_dogfood.sh --scenario=all-plan4   # 不加 --plan4 也可（--scenario=all-plan4 自动启用 plan4 模式）
#   ./run_dogfood.sh --scenario=plan4_a_greeting   # 单 plan4_* 场景也自动启用 plan4 模式
#   # 注：--plan3 与 --plan4 互斥，不能同时使用
#
# 依赖（你本机）：
#   - ssh new 已配置好（~/.ssh/config 别名 new -> 49.233.213.109）
#   - new 上 Python 3 + websockets + PyJWT 已装（见 README 自检方法）
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOCAL_SCRIPT="$SCRIPT_DIR/dogfood_test.py"
LOCAL_OVERRIDE="$SCRIPT_DIR/plan3_env_override.conf"
LOCAL_PLAN4_OVERRIDE="$SCRIPT_DIR/plan4_env_override.conf"
REMOTE_SCRIPT="/tmp/dogfood_test.py"
SERVER="new"
REMOTE_ENV="/etc/3yan/3yan-server.env"

# ---- 解析参数，识别 --plan3 flag ----
PLAN3_MODE=false
PASSTHROUGH_ARGS=()
for arg in "$@"; do
    if [[ "$arg" == "--plan3" ]]; then
        PLAN3_MODE=true
    elif [[ "$arg" == "--scenario=all-plan3" ]]; then
        # --scenario=all-plan3 自动启用 plan3 模式
        PLAN3_MODE=true
        PASSTHROUGH_ARGS+=("$arg")
    else
        PASSTHROUGH_ARGS+=("$arg")
    fi
done

# plan3 mode 下，若没有明确 --scenario=xxx，追加 --scenario=all-plan3 默认跑全部 plan3 场景
if $PLAN3_MODE; then
    HAS_SCENARIO=false
    for arg in "${PASSTHROUGH_ARGS[@]:-}"; do
        if [[ "$arg" == --scenario=* ]]; then
            HAS_SCENARIO=true
            break
        fi
    done
    if ! $HAS_SCENARIO; then
        PASSTHROUGH_ARGS+=("--scenario=all-plan3")
    fi
fi

# ---- 解析参数，识别 --plan4 flag（对称 plan3）----
PLAN4_MODE=false
PLAN4_PASSTHROUGH=()
for arg in "${PASSTHROUGH_ARGS[@]:-}"; do
    if [[ "$arg" == "--plan4" ]]; then
        PLAN4_MODE=true
    elif [[ "$arg" == "--scenario=all-plan4" ]]; then
        # --scenario=all-plan4 自动启用 plan4 模式
        PLAN4_MODE=true
        PLAN4_PASSTHROUGH+=("$arg")
    elif [[ "$arg" == --scenario=plan4_* ]]; then
        # --scenario=plan4_xxx 单场景也自动启用 plan4 模式
        PLAN4_MODE=true
        PLAN4_PASSTHROUGH+=("$arg")
    else
        PLAN4_PASSTHROUGH+=("$arg")
    fi
done
PASSTHROUGH_ARGS=("${PLAN4_PASSTHROUGH[@]:-}")

# plan4 mode 下，若没有明确 --scenario=xxx，追加 --scenario=all-plan4 默认跑全部 plan4 场景
if $PLAN4_MODE; then
    HAS_PLAN4_SCENARIO=false
    for arg in "${PASSTHROUGH_ARGS[@]:-}"; do
        if [[ "$arg" == --scenario=* ]]; then
            HAS_PLAN4_SCENARIO=true
            break
        fi
    done
    if ! $HAS_PLAN4_SCENARIO; then
        PASSTHROUGH_ARGS+=("--scenario=all-plan4")
    fi
fi

# 防呆：plan3 / plan4 互斥（本期不支持同时叠加两套 env override）
if $PLAN3_MODE && $PLAN4_MODE; then
    echo "[error] --plan3 与 --plan4 不能同时使用（两套 env override 会叠加冲突）" >&2
    exit 1
fi

if [[ ! -f "$LOCAL_SCRIPT" ]]; then
    echo "找不到 $LOCAL_SCRIPT" >&2
    exit 1
fi

# ---- 上传脚本 ----
echo "==> 上传 dogfood_test.py 到 $SERVER:$REMOTE_SCRIPT"
scp -q "$LOCAL_SCRIPT" "$SERVER:$REMOTE_SCRIPT"

# ---- 远端临时文件清理（脚本文件）----
REMOTE_CLEANUP_REGISTERED=false
cleanup_remote_script() {
    ssh "$SERVER" "rm -f $REMOTE_SCRIPT" 2>/dev/null || true
}

# ---- plan3 env override + 回滚机制 ----
# 当 PLAN3_MODE=true 时：
#   1. 测试前：备份 env 文件，追加 plan3 覆盖配置，重启服务
#   2. 测试结束/异常/Ctrl-C（trap EXIT）：恢复 env 文件，重启服务
#   3. 成功完成后删除备份；异常退出时保留备份供排查
REMOTE_ENV_BAK=""
PLAN3_ENV_APPLIED=false
REMOTE_ENV_BAK_PLAN4=""
PLAN4_ENV_APPLIED=false

rollback_plan3_env() {
    if ! $PLAN3_ENV_APPLIED; then
        return
    fi
    echo ""
    echo "==> [plan3 rollback] 恢复 $REMOTE_ENV → 重启服务..."

    # 双保险：① cp bak 覆盖，② 显式 sed 删除 plan3 override 块（防 bak 文件本身被污染）。
    # 不再吞 stderr —— 任何一步失败都要让用户看到。
    if [[ -n "$REMOTE_ENV_BAK" ]]; then
        ssh "$SERVER" "sudo cp $REMOTE_ENV_BAK $REMOTE_ENV" || {
            echo "  [error] cp bak 失败，请手动执行: sudo cp $REMOTE_ENV_BAK $REMOTE_ENV" >&2
        }
    fi
    ssh "$SERVER" "sudo sed -i '/^# -- plan3 dogfood override --\$/,/^# -- end plan3 dogfood override --\$/d' $REMOTE_ENV"

    # rollback 后立即 verify：env 文件里绝对不能再有 plan3 override 标记。
    REMAINING="$(ssh "$SERVER" "sudo grep -c 'plan3 dogfood override' $REMOTE_ENV || true")"
    if [[ "$REMAINING" != "0" ]]; then
        echo "  [error] env 文件仍残留 plan3 override 标记 ${REMAINING} 行，请手动检查 $REMOTE_ENV" >&2
    fi

    ssh "$SERVER" "sudo systemctl restart 3yan-server" || {
        echo "  [warn] 重启 3yan-server 失败，请手动重启" >&2
    }
    echo "==> [plan3 rollback] 等待服务启动（最多 30s）..."
    ssh "$SERVER" "
        for i in \$(seq 1 30); do
            if systemctl is-active --quiet 3yan-server; then
                echo '  服务已就绪'
                exit 0
            fi
            sleep 1
        done
        echo '  [warn] 30s 后服务仍未就绪' >&2
        exit 0
    " || true
    PLAN3_ENV_APPLIED=false
    echo "==> [plan3 rollback] 完成"
}

rollback_plan4_env() {
    if ! $PLAN4_ENV_APPLIED; then
        return
    fi
    echo ""
    echo "==> [plan4 rollback] 恢复 $REMOTE_ENV → 重启服务..."

    # 双保险：① cp bak 覆盖，② 显式 sed 删除 plan4 override 块（防 bak 文件本身被污染）。
    # 不再吞 stderr —— 任何一步失败都要让用户看到。
    if [[ -n "$REMOTE_ENV_BAK_PLAN4" ]]; then
        ssh "$SERVER" "sudo cp $REMOTE_ENV_BAK_PLAN4 $REMOTE_ENV" || {
            echo "  [error] cp bak 失败，请手动执行: sudo cp $REMOTE_ENV_BAK_PLAN4 $REMOTE_ENV" >&2
        }
    fi
    ssh "$SERVER" "sudo sed -i '/^# -- plan4 dogfood override --\$/,/^# -- end plan4 dogfood override --\$/d' $REMOTE_ENV"

    # rollback 后立即 verify：env 文件里绝对不能再有 plan4 override 标记。
    REMAINING_PLAN4="$(ssh "$SERVER" "sudo grep -c 'plan4 dogfood override' $REMOTE_ENV || true")"
    if [[ "$REMAINING_PLAN4" != "0" ]]; then
        echo "  [error] env 文件仍残留 plan4 override 标记 ${REMAINING_PLAN4} 行，请手动检查 $REMOTE_ENV" >&2
    fi

    ssh "$SERVER" "sudo systemctl restart 3yan-server" || {
        echo "  [warn] 重启 3yan-server 失败，请手动重启" >&2
    }
    echo "==> [plan4 rollback] 等待服务启动（最多 30s）..."
    ssh "$SERVER" "
        for i in \$(seq 1 30); do
            if systemctl is-active --quiet 3yan-server; then
                echo '  服务已就绪'
                exit 0
            fi
            sleep 1
        done
        echo '  [warn] 30s 后服务仍未就绪' >&2
        exit 0
    " || true
    PLAN4_ENV_APPLIED=false
    echo "==> [plan4 rollback] 完成"
}

cleanup_all() {
    rollback_plan4_env
    rollback_plan3_env
    cleanup_remote_script
}
trap cleanup_all EXIT

if $PLAN3_MODE; then
    if [[ ! -f "$LOCAL_OVERRIDE" ]]; then
        echo "找不到 plan3 env override 文件: $LOCAL_OVERRIDE" >&2
        exit 1
    fi

    TIMESTAMP="$(date +%Y%m%d_%H%M%S)"
    REMOTE_ENV_BAK="${REMOTE_ENV}.bak-${TIMESTAMP}"

    echo "==> [plan3] 备份 $REMOTE_ENV → $REMOTE_ENV_BAK"
    ssh "$SERVER" "sudo cp $REMOTE_ENV $REMOTE_ENV_BAK"

    echo "==> [plan3] 追加阈值覆盖配置到 $REMOTE_ENV"
    # 先删掉可能存在的旧 plan3 覆盖（防止重复追加），再写入
    OVERRIDE_CONTENT="$(cat "$LOCAL_OVERRIDE")"
    ssh "$SERVER" "
        # 删除上次可能遗留的 plan3 标记行（防止重复追加）
        sudo sed -i '/^# -- plan3 dogfood override --\$/,/^# -- end plan3 dogfood override --\$/d' $REMOTE_ENV

        # 追加新的 plan3 覆盖块
        printf '\n# -- plan3 dogfood override --\n' | sudo tee -a $REMOTE_ENV > /dev/null
        cat <<'_OVERRIDE_EOF_' | sudo tee -a $REMOTE_ENV > /dev/null
$OVERRIDE_CONTENT
_OVERRIDE_EOF_
        printf '# -- end plan3 dogfood override --\n' | sudo tee -a $REMOTE_ENV > /dev/null
    "
    PLAN3_ENV_APPLIED=true

    echo "==> [plan3] 重启 3yan-server 使配置生效..."
    ssh "$SERVER" "sudo systemctl restart 3yan-server"

    echo "==> [plan3] 等待服务启动（最多 60s，三重检查 port+active+Started 日志）..."
    SERVICE_UP=false
    for i in $(seq 1 60); do
        PORT_OK="$(ssh "$SERVER" "ss -tln | grep -q ':8080 '" 2>/dev/null && echo y || echo n)"
        ACTIVE_OK="$(ssh "$SERVER" "systemctl is-active 3yan-server" 2>/dev/null || echo failed)"
        STARTED_OK="$(ssh "$SERVER" "journalctl -u 3yan-server --since '90 seconds ago' --no-pager 2>/dev/null | grep -q 'Started SanyanApplication'" 2>/dev/null && echo y || echo n)"
        if [[ "$PORT_OK" == "y" && "$ACTIVE_OK" == "active" && "$STARTED_OK" == "y" ]]; then
            SERVICE_UP=true
            break
        fi
        sleep 1
    done

    if ! $SERVICE_UP; then
        echo "  [error] 3yan-server 60s 内未就绪 (port=${PORT_OK} active=${ACTIVE_OK} started=${STARTED_OK})" >&2
        exit 1
    fi
    echo "  服务已就绪（port=LISTEN active 应用已 Started）"
fi

if $PLAN4_MODE; then
    if [[ ! -f "$LOCAL_PLAN4_OVERRIDE" ]]; then
        echo "找不到 plan4 env override 文件: $LOCAL_PLAN4_OVERRIDE" >&2
        exit 1
    fi

    TIMESTAMP="$(date +%Y%m%d_%H%M%S)"
    REMOTE_ENV_BAK_PLAN4="${REMOTE_ENV}.bak.plan4-${TIMESTAMP}"

    echo "==> [plan4] 备份 $REMOTE_ENV → $REMOTE_ENV_BAK_PLAN4"
    ssh "$SERVER" "sudo cp $REMOTE_ENV $REMOTE_ENV_BAK_PLAN4"

    echo "==> [plan4] 追加阈值覆盖配置到 $REMOTE_ENV"
    # 注：$REMOTE_ENV 是 systemd EnvironmentFile（不是 shell source），整行到行尾即值，
    # 值一律不加引号 —— cron 值里的空格会被原样保留，加引号反而把引号算进值。
    # 先删掉可能存在的旧 plan4 覆盖（防止重复追加），再原样追加 conf 内容。
    OVERRIDE_CONTENT_PLAN4="$(cat "$LOCAL_PLAN4_OVERRIDE")"
    ssh "$SERVER" "
        # 删除上次可能遗留的 plan4 标记行（防止重复追加）
        sudo sed -i '/^# -- plan4 dogfood override --\$/,/^# -- end plan4 dogfood override --\$/d' $REMOTE_ENV

        # 追加新的 plan4 覆盖块
        printf '\n# -- plan4 dogfood override --\n' | sudo tee -a $REMOTE_ENV > /dev/null
        cat <<'_OVERRIDE_EOF_' | sudo tee -a $REMOTE_ENV > /dev/null
$OVERRIDE_CONTENT_PLAN4
_OVERRIDE_EOF_
        printf '# -- end plan4 dogfood override --\n' | sudo tee -a $REMOTE_ENV > /dev/null
    "
    PLAN4_ENV_APPLIED=true

    echo "==> [plan4] 重启 3yan-server 使配置生效..."
    ssh "$SERVER" "sudo systemctl restart 3yan-server"

    echo "==> [plan4] 等待服务启动（最多 60s，三重检查 port+active+Started 日志）..."
    SERVICE_UP_PLAN4=false
    for i in $(seq 1 60); do
        PORT_OK="$(ssh "$SERVER" "ss -tln | grep -q ':8080 '" 2>/dev/null && echo y || echo n)"
        ACTIVE_OK="$(ssh "$SERVER" "systemctl is-active 3yan-server" 2>/dev/null || echo failed)"
        STARTED_OK="$(ssh "$SERVER" "journalctl -u 3yan-server --since '90 seconds ago' --no-pager 2>/dev/null | grep -q 'Started SanyanApplication'" 2>/dev/null && echo y || echo n)"
        if [[ "$PORT_OK" == "y" && "$ACTIVE_OK" == "active" && "$STARTED_OK" == "y" ]]; then
            SERVICE_UP_PLAN4=true
            break
        fi
        sleep 1
    done

    if ! $SERVICE_UP_PLAN4; then
        echo "  [error] 3yan-server 60s 内未就绪 (port=${PORT_OK} active=${ACTIVE_OK} started=${STARTED_OK})" >&2
        exit 1
    fi
    echo "  服务已就绪（port=LISTEN active 应用已 Started）"
fi

# ---- 跑测试 ----
echo "==> ssh $SERVER 跑 dogfood_test.py ${PASSTHROUGH_ARGS[*]:-}"

# 注：原 SANYAN_OLD_SSH + ssh-agent forwarding 是给 degradation 场景用的（去 old 上 stop sanyan-embedding 服务）。
# 2026-05-17 embedding 改用硅基流动 API 后 degradation 场景已删除，本脚本不再需要 agent forwarding。

# ssh -tt 强制分配 pty——让远端 python stdout 走 pty（行 flush）而不是 pipe（block buffer，
# ssh client 默认要等远端进程退出才一次性 flush）。配合 python3 -u 双层保险，实现实时输出。
EXIT_CODE=0
ssh -tt "$SERVER" "python3 -u $REMOTE_SCRIPT ${PASSTHROUGH_ARGS[*]:-}" || EXIT_CODE=$?

# ---- plan3 成功时删除备份 ----
if $PLAN3_MODE && [[ $EXIT_CODE -eq 0 ]] && [[ -n "$REMOTE_ENV_BAK" ]]; then
    echo "==> [plan3] 测试通过，删除备份文件 $REMOTE_ENV_BAK"
    ssh "$SERVER" "sudo rm -f $REMOTE_ENV_BAK" 2>/dev/null || true
fi

# ---- plan4 成功时删除备份 ----
if $PLAN4_MODE && [[ $EXIT_CODE -eq 0 ]] && [[ -n "$REMOTE_ENV_BAK_PLAN4" ]]; then
    echo "==> [plan4] 测试通过，删除备份文件 $REMOTE_ENV_BAK_PLAN4"
    ssh "$SERVER" "sudo rm -f $REMOTE_ENV_BAK_PLAN4" 2>/dev/null || true
fi

exit "$EXIT_CODE"
