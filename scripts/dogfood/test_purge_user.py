"""purge_user.py + dogfood_test.py 清空/防呆逻辑的单元测试。

运行：cd server/scripts/dogfood && pytest test_purge_user.py -v
"""

import sys
import pathlib

import pytest

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import dogfood_test as dt  # noqa: E402


# 库里真正挂用户数据的 7 张表（information_schema 实查得到）。
EXPECTED_USER_DATA_TABLES = {
    "message",
    "memory_summaries",
    "memory_profiles",
    "chat_embeddings",
    "relationships",
    "intimacy_logs",
    "relationship_milestones",
}


class _FakeDb:
    """记录所有 execute 的 SQL，不真连库。"""

    def __init__(self) -> None:
        self.executed: list[str] = []

    def execute(self, sql: str) -> None:
        self.executed.append(sql)

    def query(self, sql: str) -> list:
        self.executed.append(sql)
        return []


# ---------------- USER_DATA_TABLES 单点清单 ----------------

def test_user_data_tables_恰好覆盖7张挂用户数据的表():
    assert set(dt.USER_DATA_TABLES) == EXPECTED_USER_DATA_TABLES


# ---------------- purge_all_user_data ----------------

def test_purge_对每张表都发出带user_id过滤的delete(monkeypatch):
    monkeypatch.setattr(dt, "redis_cmd", lambda *a: "")
    db = _FakeDb()

    dt.purge_all_user_data(db, 1, dt.Logger(verbose=False))

    for table in dt.USER_DATA_TABLES:
        assert any(
            s == f"DELETE FROM {table} WHERE user_id = 1" for s in db.executed
        ), f"缺少对 {table} 的删除"


def test_purge_绝不删除users账号行(monkeypatch):
    monkeypatch.setattr(dt, "redis_cmd", lambda *a: "")
    db = _FakeDb()

    dt.purge_all_user_data(db, 1, dt.Logger(verbose=False))

    assert all("DELETE FROM users" not in s for s in db.executed)


def test_purge_不删除全局RAG队列影响其他用户(monkeypatch):
    redis_calls: list[tuple] = []
    monkeypatch.setattr(dt, "redis_cmd", lambda *a: redis_calls.append(a) or "")
    db = _FakeDb()

    dt.purge_all_user_data(db, 1, dt.Logger(verbose=False))

    flat = [arg for call in redis_calls for arg in call]
    assert dt.RAG_INDEX_QUEUE_KEY not in flat


def test_purge_清理该用户的redis节流streak行为key(monkeypatch):
    redis_calls: list[tuple] = []
    monkeypatch.setattr(dt, "redis_cmd", lambda *a: redis_calls.append(a) or "")
    db = _FakeDb()

    dt.purge_all_user_data(db, 7, dt.Logger(verbose=False))

    flat = " ".join(arg for call in redis_calls for arg in call)
    assert f"{dt.STREAK_KEY_PREFIX}7" in flat
    assert f"{dt.THROTTLE_KEY_PREFIX}7:" in flat
    assert f"{dt.BEHAVIOR_KEY_PREFIX}7:" in flat


# ---------------- dogfood ≥900 防呆 ----------------

def test_validate_dogfood_user_id_拒绝小于900的真实用户():
    for bad in (1, 2, 899):
        with pytest.raises(ValueError):
            dt.validate_dogfood_user_id(bad)


def test_validate_dogfood_user_id_放行900及以上的隔离测试号():
    for ok in (900, 901, 919):
        dt.validate_dogfood_user_id(ok)  # 不抛即通过


# ---------------- 独立脚本 purge_user.py 参数解析 ----------------

def test_parse_user_ids_解析多个整数():
    import purge_user as pu

    assert pu.parse_user_ids(["1", "2", "3"]) == [1, 2, 3]


def test_parse_user_ids_空输入报错():
    import purge_user as pu

    with pytest.raises(ValueError):
        pu.parse_user_ids([])


def test_parse_user_ids_非整数报错():
    import purge_user as pu

    with pytest.raises(ValueError):
        pu.parse_user_ids(["abc"])
