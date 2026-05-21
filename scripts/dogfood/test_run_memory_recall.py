"""dogfood_test.py 内部 helper 的单元测试。

运行：cd server/scripts/dogfood && pytest test_run_memory_recall.py -v
"""

import sys
import pathlib

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import dogfood_test as dt  # noqa: E402


# ---------------- RECALL_DISTRACT_MESSAGES 保护测试 ----------------

def test_distract_pool_长度恰好35():
    """填充池数量必须固定 35，骨架据此推 plant 出短期窗口 32 条。"""
    assert len(dt.RECALL_DISTRACT_MESSAGES) == 35


def test_distract_pool_不含场景关键词避免污染():
    """
    填充消息绝不能巧合提到任何场景的 expected_keywords，
    否则 AI 在 distract 阶段回应时若顺嘴提到，会假阳性 PASS 召回测试。
    """
    forbidden = [
        # profile 场景关键词
        "绵阳", "四川", "川", "杭州", "王莎莎", "汤圆",
        # summary 场景关键词
        "寿司", "刺身", "三文鱼",
        # rag 场景关键词
        "成都", "周三", "星期三", "出差", "春熙路",
    ]
    for msg in dt.RECALL_DISTRACT_MESSAGES:
        for word in forbidden:
            assert word not in msg, f"填充消息 '{msg}' 含场景关键词 '{word}'"


import asyncio
import pytest
from unittest.mock import MagicMock


# ---------------- _wait_table_has_row 单测 ----------------

@pytest.mark.asyncio
async def test_wait_table_has_row_首次查到行立即返回True(monkeypatch):
    """db.query 第一次就返回非空 → wait 立即 True，不 sleep。"""
    db = MagicMock()
    db.query.return_value = [["1"]]  # COUNT(*) = 1

    # 拦截 asyncio.sleep 防止真等
    sleep_calls = []
    async def fake_sleep(d):
        sleep_calls.append(d)
    monkeypatch.setattr(dt.asyncio, "sleep", fake_sleep)

    result = await dt._wait_table_has_row(db, "memory_profiles", 901, dt.Logger(False))

    assert result is True
    assert sleep_calls == []  # 首次命中，不该 sleep
    db.query.assert_called_once()


@pytest.mark.asyncio
async def test_wait_table_has_row_30s超时返回False(monkeypatch):
    """db.query 始终空 + 模拟 time 流逝 30s → 返回 False。"""
    db = MagicMock()
    db.query.return_value = [["0"]]  # COUNT(*) = 0

    # 用 fake monotonic 模拟时间流逝：每次调用 +5s
    counter = {"n": 0}
    def fake_monotonic():
        counter["n"] += 1
        return counter["n"] * 5.0  # 0, 5, 10, 15, ... 30s 后超时

    async def fake_sleep(d):
        pass  # 不真等

    monkeypatch.setattr(dt.time, "monotonic", fake_monotonic)
    monkeypatch.setattr(dt.asyncio, "sleep", fake_sleep)

    result = await dt._wait_table_has_row(db, "memory_profiles", 901, dt.Logger(False), timeout=30.0)

    assert result is False


@pytest.mark.asyncio
async def test_wait_3个包装函数指向正确的表名(monkeypatch):
    """_wait_profile_landed / _wait_summary_landed / _wait_rag_chunk_landed 应查对应表。"""
    called_tables = []

    async def fake_wait(db, table, user_id, log, timeout=30.0):
        called_tables.append(table)
        return True

    monkeypatch.setattr(dt, "_wait_table_has_row", fake_wait)

    db = MagicMock()
    log = dt.Logger(False)
    await dt._wait_profile_landed(db, 901, log)
    await dt._wait_summary_landed(db, 901, log)
    await dt._wait_rag_chunk_landed(db, 901, log)

    assert called_tables == ["memory_profiles", "memory_summaries", "chat_embeddings"]
