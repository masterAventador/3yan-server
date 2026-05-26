#!/usr/bin/env python3
"""purge_user.py —— 清空指定用户的全部业务数据，只保留 users 账号行本身。

复用 dogfood_test 的 DbHandle / redis 封装 / USER_DATA_TABLES 单点清单，
对该用户的全部 7 张业务表 DELETE WHERE user_id + 清该用户的 Redis key。

运行在 new 服务器本地（用本机 psql + redis-cli）：
    python3 purge_user.py 1            # 清空 user_id=1 的全部数据
    python3 purge_user.py 1 2 3        # 批量

本机一键（scp + 远程执行）见 run_purge.sh：
    ./run_purge.sh 1

⚠️ 直接删，无 dry-run / 二次确认。
⚠️ 不走 dogfood 的 ≥900 防呆——本工具就是用来清真实账号的，自行小心。
"""

import sys
import pathlib

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import dogfood_test as dt  # noqa: E402


def parse_user_ids(argv: list) -> list:
    """把命令行参数解析成 user_id 整数列表。空输入 / 非整数都抛 ValueError。"""
    if not argv:
        raise ValueError("至少要传一个 user_id，用法: python3 purge_user.py <user_id> [<user_id> ...]")
    try:
        return [int(a) for a in argv]
    except ValueError:
        raise ValueError(f"user_id 必须是整数，收到: {argv}")


def main() -> None:
    try:
        user_ids = parse_user_ids(sys.argv[1:])
    except ValueError as e:
        print(str(e), file=sys.stderr)
        sys.exit(2)

    env = dt.read_env()
    db = dt.DbHandle(
        pg_user=env.get("PG_USER", "sanyan"),
        pg_password=env.get("PG_PASSWORD", ""),
    )
    log = dt.Logger(verbose=True)
    for uid in user_ids:
        dt.purge_all_user_data(db, uid, log)
    log.info(f"==> [purge] 完成，已清空 user_ids={user_ids}（users 账号行保留）")


if __name__ == "__main__":
    main()
