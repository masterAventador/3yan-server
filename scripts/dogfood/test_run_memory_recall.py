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


# ---------------- run_memory_recall 骨架测试 ----------------

def _fake_wsreply(text: str) -> dt.WsReply:
    """构造一个带 1 个 AI 气泡的 fake WsReply。"""
    r = dt.WsReply()
    r.new_messages.append({"id": 1, "senderType": "AI", "content": text, "createdAt": "x"})
    return r


def _fake_empty_wsreply() -> dt.WsReply:
    """AI 没回任何气泡的 WsReply（链路异常）。"""
    return dt.WsReply()


@pytest.mark.asyncio
async def test_run_memory_recall_6步按序执行(monkeypatch):
    """骨架按 clean → plant_chat → wait1 → distract_chat → wait2 → probe_chat 顺序执行。"""
    call_order = []

    def fake_clean(db, user_id, log):
        call_order.append("clean")

    async def fake_chat(token, contents, log, wait_between=0.5):
        if contents == ["plant1"]:
            call_order.append("plant_chat")
        elif contents == ["d1", "d2"]:
            call_order.append("distract_chat")
        elif contents == ["probe"]:
            call_order.append("probe_chat")
        return [_fake_wsreply("AI 回 含 关键词") for _ in contents]

    async def fake_wait1(db, user_id, log):
        call_order.append("wait1")
        return True

    async def fake_wait2(db, user_id, log):
        call_order.append("wait2")
        return True

    monkeypatch.setattr(dt, "clean_test_data", fake_clean)
    monkeypatch.setattr(dt, "chat", fake_chat)
    monkeypatch.setattr(dt, "RECALL_DISTRACT_MESSAGES", ["d1", "d2", "d3"])  # 缩小让 distract_count=2 取前 2 条

    result = await dt.run_memory_recall(
        scenario_name="test_scenario",
        plant_messages=["plant1"],
        post_plant_wait_fn=fake_wait1,
        distract_count=2,
        post_distract_wait_fn=fake_wait2,
        probe_message="probe",
        expected_keywords=["关键词"],
        token="t",
        db=MagicMock(),
        user_id=900,
        character_id=1,
        log=dt.Logger(False),
    )

    assert call_order == ["clean", "plant_chat", "wait1", "distract_chat", "wait2", "probe_chat"]
    assert result.status == "PASS"


@pytest.mark.asyncio
async def test_run_memory_recall_关键词命中PASS(monkeypatch):
    """probe AI 回复中含任一 expected_keyword → PASS。"""
    monkeypatch.setattr(dt, "clean_test_data", lambda *a, **kw: None)

    async def fake_chat(token, contents, log, wait_between=0.5):
        return [_fake_wsreply("我记得你说过绵阳的事") for _ in contents]

    monkeypatch.setattr(dt, "chat", fake_chat)
    monkeypatch.setattr(dt, "RECALL_DISTRACT_MESSAGES", ["d"])

    result = await dt.run_memory_recall(
        scenario_name="test",
        plant_messages=["plant"],
        post_plant_wait_fn=None,
        distract_count=1,
        post_distract_wait_fn=None,
        probe_message="probe",
        expected_keywords=["绵阳", "四川"],
        token="t", db=MagicMock(), user_id=900, character_id=1, log=dt.Logger(False),
    )

    assert result.status == "PASS"
    assert "绵阳" in result.detail


@pytest.mark.asyncio
async def test_run_memory_recall_关键词全未命中FAIL(monkeypatch):
    """probe AI 回复完全不含任何 expected_keyword → FAIL，detail 含 reply 全文。"""
    monkeypatch.setattr(dt, "clean_test_data", lambda *a, **kw: None)

    async def fake_chat(token, contents, log, wait_between=0.5):
        return [_fake_wsreply("我不太记得了，你说说看") for _ in contents]

    monkeypatch.setattr(dt, "chat", fake_chat)
    monkeypatch.setattr(dt, "RECALL_DISTRACT_MESSAGES", ["d"])

    result = await dt.run_memory_recall(
        scenario_name="test",
        plant_messages=["plant"],
        post_plant_wait_fn=None,
        distract_count=1,
        post_distract_wait_fn=None,
        probe_message="probe",
        expected_keywords=["绵阳", "四川"],
        token="t", db=MagicMock(), user_id=900, character_id=1, log=dt.Logger(False),
    )

    assert result.status == "FAIL"
    assert "我不太记得了" in result.detail
    assert "绵阳" in result.detail


@pytest.mark.asyncio
async def test_run_memory_recall_wait_fn返回False立即FAIL(monkeypatch):
    """post_plant_wait_fn 返回 False（落库超时）→ 骨架直接 FAIL，不进 distract。"""
    monkeypatch.setattr(dt, "clean_test_data", lambda *a, **kw: None)

    chat_call_count = {"n": 0}

    async def fake_chat(token, contents, log, wait_between=0.5):
        chat_call_count["n"] += 1
        return [_fake_wsreply("OK") for _ in contents]

    async def fake_wait_timeout(db, user_id, log):
        return False

    monkeypatch.setattr(dt, "chat", fake_chat)

    result = await dt.run_memory_recall(
        scenario_name="test",
        plant_messages=["plant"],
        post_plant_wait_fn=fake_wait_timeout,
        distract_count=1,
        post_distract_wait_fn=None,
        probe_message="probe",
        expected_keywords=["OK"],
        token="t", db=MagicMock(), user_id=900, character_id=1, log=dt.Logger(False),
    )

    assert result.status == "FAIL"
    assert "未在 30s 内落库" in result.detail or "上游" in result.detail
    assert chat_call_count["n"] == 1


# ---------------- run_summary regression：summary_text 含 \n 不应 unpack 错 ----------------

def test_run_summary查summary表必须用regexp_replace处理换行():
    """防回归：DbHandle.query 用 splitlines 切 psql 输出，summary_text 含 \\n 时会被切散成
    多行单列，导致 `latest_summary, msg_cnt = rows[0]` ValueError('expected 2, got 1')。
    SQL 层必须用 regexp_replace 把 \\n 替换为空格，保证 query 返回 1 行 2 列。
    """
    import inspect
    src = inspect.getsource(dt.run_summary)
    # SELECT summary_text 的语句必须包到 regexp_replace 里
    has_safe_select = "regexp_replace(summary_text" in src
    assert has_safe_select, (
        "run_summary 必须用 regexp_replace(summary_text, E'\\n', ' ', 'g') 包 SELECT，"
        "否则 AI 生成的多行 summary_text 会让 DbHandle.query splitlines 切散成 N 行单列，"
        "rows[0] 解包成 (latest_summary, msg_cnt) 时 ValueError。"
    )
