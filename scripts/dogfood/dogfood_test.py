#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Plan 2 dogfood E2E 测试 harness。

设计为在 **new 服务器（49.233.213.109）本地** 执行：
- WS 直接打 ws://localhost:8080/ws，避免被本地宽带的 NAT/中转干扰
- psql / redis-cli 命中本机服务，零网络开销

4 个场景（profile / throttle / summary / rag），每个场景实现一个
`run_<scenario>()` + 一个 `assert_<scenario>()`，PASS/FAIL/SKIP 摘要 + 退出码语义。

依赖（new 上已装）:
- Python 3.13+
- websockets (16.0)
- PyJWT (2.12)
- 本机 psql / redis-cli
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import subprocess
import sys
import time
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable, Optional

import jwt  # PyJWT
import websockets


# ----------------------------- 常量 -----------------------------

ENV_FILE = "/opt/3yan/3yan-server/.env"
WS_URL_TEMPLATE = "ws://localhost:8080/ws?token={token}"

# 与 MessageConstants / MemoryConstants 对齐（写死，服务端改了这里也要改）
SUMMARY_TRIGGER_THRESHOLD = 30
PROFILE_EXTRACT_THROTTLE_MINUTES = 5
RAG_CHUNK_MIN_SIZE = 5

THROTTLE_KEY_PREFIX = "sanyan:memory:profile:throttle:"
RAG_INDEX_QUEUE_KEY = "sanyan:memory:rag:index-queue"

# WS 一轮 AI 回复硬超时兜底（秒）。正常路径靠 server 的 turn_complete 精确结束；
# 服务挂掉不发 turn_complete 时这个兜底才生效。
REPLY_HARD_TIMEOUT_SECONDS = 90.0

# 抽取异步触发后等待落库的最长时长（秒）
PROFILE_REFRESH_WAIT_SECONDS = 30.0
SUMMARY_REFRESH_WAIT_SECONDS = 30.0
RAG_INDEX_WAIT_SECONDS = 30.0


# ----------------------------- log -----------------------------

class Logger:
    """轻量 log，按 verbose 级别筛输出。"""

    def __init__(self, verbose: bool) -> None:
        self.verbose = verbose

    def info(self, msg: str) -> None:
        print(msg, flush=True)

    def debug(self, msg: str) -> None:
        if self.verbose:
            print(f"  [debug] {msg}", flush=True)

    def warn(self, msg: str) -> None:
        print(f"  [warn]  {msg}", flush=True)


# ----------------------------- 子进程辅助 -----------------------------

def run_cmd(cmd: list[str], *, check: bool = True, input_str: Optional[str] = None) -> str:
    """同步跑 shell 命令，返回 stdout（strip 后）。失败抛错。"""
    result = subprocess.run(
        cmd,
        capture_output=True,
        text=True,
        input=input_str,
        timeout=60,
    )
    if check and result.returncode != 0:
        raise RuntimeError(
            f"command failed (exit={result.returncode}): {' '.join(cmd)}\n"
            f"stderr: {result.stderr.strip()}"
        )
    return result.stdout.strip()


def read_env(path: str = ENV_FILE) -> dict[str, str]:
    """读 /opt/3yan/3yan-server/.env 成 dict。形如 `KEY=value`，忽略 # 注释与空行。"""
    env: dict[str, str] = {}
    p = Path(path)
    if not p.is_file():
        raise FileNotFoundError(f"env 文件不存在: {path}")
    for raw in p.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, _, v = line.partition("=")
        # 去掉可能的 export 前缀和成对引号
        if k.startswith("export "):
            k = k[len("export "):].strip()
        v = v.strip()
        if (v.startswith('"') and v.endswith('"')) or (v.startswith("'") and v.endswith("'")):
            v = v[1:-1]
        env[k.strip()] = v
    return env


# ----------------------------- DB / Redis 助手 -----------------------------

@dataclass
class DbHandle:
    """简单 wrapper：用 PGPASSWORD env 调本机 psql。"""
    pg_user: str
    pg_password: str
    pg_database: str = "sanyan"

    def query(self, sql: str) -> list[list[str]]:
        """返回 [[col1, col2, ...], ...]。tab 分隔。"""
        env = {**os.environ, "PGPASSWORD": self.pg_password}
        result = subprocess.run(
            ["psql", "-h", "localhost", "-U", self.pg_user, "-d", self.pg_database,
             "-tAF", "\t", "-c", sql],
            capture_output=True, text=True, env=env, timeout=30,
        )
        if result.returncode != 0:
            raise RuntimeError(f"psql 失败: {result.stderr.strip()}")
        out = result.stdout.strip()
        if not out:
            return []
        return [line.split("\t") for line in out.splitlines()]

    def execute(self, sql: str) -> None:
        env = {**os.environ, "PGPASSWORD": self.pg_password}
        result = subprocess.run(
            ["psql", "-h", "localhost", "-U", self.pg_user, "-d", self.pg_database,
             "-c", sql],
            capture_output=True, text=True, env=env, timeout=60,
        )
        if result.returncode != 0:
            raise RuntimeError(f"psql exec 失败: {result.stderr.strip()}\nSQL: {sql}")


def redis_cmd(*args: str) -> str:
    return run_cmd(["redis-cli", *args], check=True)


# ----------------------------- JWT mint -----------------------------

def mint_jwt(secret: str, user_id: int, expiration_days: int = 30) -> str:
    """
    Mint HS512 JWT 兼容 jjwt（io.jsonwebtoken）的格式。
    server 端用 Keys.hmacShaKeyFor + signWith()，当 key >= 64 byte 时默认 HS512。
    用本项目当前 secret（128 字符），就是 HS512。
    """
    now = int(time.time())
    payload = {
        "sub": str(user_id),
        "iat": now,
        "exp": now + expiration_days * 86400,
    }
    return jwt.encode(payload, secret, algorithm="HS512")


# ----------------------------- WS 客户端 -----------------------------

@dataclass
class WsReply:
    """一次 send_message 收到的所有 server 推送的归并。"""
    ack_server_msg_id: Optional[int] = None
    typing_count: int = 0
    new_messages: list[dict[str, Any]] = field(default_factory=list)
    errors: list[dict[str, Any]] = field(default_factory=list)

    @property
    def ai_texts(self) -> list[str]:
        return [m.get("content", "") for m in self.new_messages]

    @property
    def ai_concat(self) -> str:
        return "\n".join(self.ai_texts)


async def send_one(ws, content: str, log: Logger) -> WsReply:
    """
    发一条 user 消息，靠 server 的 turn_complete 事件精确判定结束（无需静默期猜测）。

    server 推送顺序：ack → typing → new_message (×N，每条之前可能再来一次 typing) → turn_complete。
    turn_complete 是 2026-05-17 新增协议事件，关联本 user 消息 clientMsgId，成功/异常路径都发。
    REPLY_HARD_TIMEOUT_SECONDS 仍作为兜底（万一服务挂了不发 turn_complete）。
    """
    client_msg_id = str(uuid.uuid4())
    payload = {"type": "send_message", "content": content, "clientMsgId": client_msg_id}
    log.debug(f"→ send_message: {content[:60]}")
    await ws.send(json.dumps(payload))

    reply = WsReply()
    start = time.monotonic()
    while True:
        remaining_hard = REPLY_HARD_TIMEOUT_SECONDS - (time.monotonic() - start)
        if remaining_hard <= 0:
            log.warn("hard timeout 90s 无 turn_complete，强制结束等待 AI 回复（可能服务挂了）")
            break

        try:
            raw = await asyncio.wait_for(ws.recv(), timeout=remaining_hard)
        except asyncio.TimeoutError:
            log.warn("hard timeout 触发 recv 超时，结束等待")
            break

        try:
            msg = json.loads(raw)
        except Exception:
            log.warn(f"无法解析 ws frame: {raw[:120]!r}")
            continue

        mtype = msg.get("type")
        if mtype == "ack":
            reply.ack_server_msg_id = msg.get("serverMsgId")
            log.debug(f"← ack serverMsgId={reply.ack_server_msg_id}")
        elif mtype == "typing":
            reply.typing_count += 1
            log.debug("← typing")
        elif mtype == "new_message":
            # WsNewMessage 序列化字段是 "message"（不是 "data"）：
            # {"type":"new_message","message":{"id":...,"senderType":"AI","content":"...","createdAt":"..."}}
            data = msg.get("message", {})
            reply.new_messages.append(data)
            log.debug(f"← new_message [{data.get('id')}]: {str(data.get('content',''))[:80]}")
        elif mtype == "error":
            reply.errors.append(msg)
            log.warn(f"← error: {msg}")
        elif mtype == "turn_complete":
            # 关键：服务端明确告诉我们 AI 这一轮气泡推完了（含异常路径），立刻 break
            log.debug(f"← turn_complete messageCount={msg.get('messageCount')}")
            break
        else:
            log.debug(f"← (其它) {mtype}: {raw[:120]}")

    return reply


async def chat(token: str, contents: list[str], log: Logger,
               wait_between: float = 0.5) -> list[WsReply]:
    """打开一次 WS 连接，按顺序发若干条消息，每条等回复完成后再发下一条。"""
    url = WS_URL_TEMPLATE.format(token=token)
    replies: list[WsReply] = []
    log.debug(f"WS 连接 {url}")
    async with websockets.connect(url, ping_interval=20, ping_timeout=20) as ws:
        for i, content in enumerate(contents):
            log.debug(f"[{i+1}/{len(contents)}] 发送...")
            r = await send_one(ws, content, log)
            replies.append(r)
            if wait_between > 0:
                await asyncio.sleep(wait_between)
    return replies


# ----------------------------- 清理 -----------------------------

def clean_test_data(db: DbHandle, user_id: int, log: Logger) -> None:
    """
    把 user_id 的 message / memory_summaries / memory_profiles / chat_embeddings 全清掉，
    + Redis 节流 key + RAG queue。不动 users / ai_character。
    """
    log.info(f"==> [clean] 清理 user_id={user_id} 的测试数据")
    db.execute(f"DELETE FROM chat_embeddings WHERE user_id = {user_id}")
    db.execute(f"DELETE FROM memory_summaries WHERE user_id = {user_id}")
    db.execute(f"DELETE FROM memory_profiles WHERE user_id = {user_id}")
    db.execute(f"DELETE FROM message WHERE user_id = {user_id}")

    # Redis：删该用户的 profile 节流 key（character 范围全清）
    keys = redis_cmd("--scan", "--pattern", f"{THROTTLE_KEY_PREFIX}{user_id}:*")
    for k in keys.splitlines():
        if k.strip():
            redis_cmd("DEL", k.strip())
    # RAG 索引队列整队列删（影响所有用户，但这是 dev 环境，可接受）
    redis_cmd("DEL", RAG_INDEX_QUEUE_KEY)
    log.debug("[clean] 完成")


def clear_profile_throttle(user_id: int, character_id: int) -> None:
    """单独清节流 key，让 profile 抽取能立刻再触发。"""
    redis_cmd("DEL", f"{THROTTLE_KEY_PREFIX}{user_id}:{character_id}")


# ----------------------------- 断言结果数据结构 -----------------------------

@dataclass
class ScenarioResult:
    name: str
    status: str  # PASS / FAIL / SKIP
    detail: str

    def line(self) -> str:
        return f"[{self.status:<4}] {self.name}: {self.detail}"


# ----------------------------- 场景 1: profile 抽取 -----------------------------

PROFILE_EXTRACT_MESSAGES = [
    "我叫张三，今年28岁。",
    "我是一个前端工程师，最近转岗想做产品经理。",
    "我家里养了一只橘猫，叫橘子，超粘人。",
    "我现在住在北京，老家是浙江杭州的。",
    "周末我喜欢看电影，特别爱诺兰的片子，最近迷上了打羽毛球。",
]


def wait_for_profile(db: DbHandle, user_id: int, character_id: int,
                     min_len: int = 20, log: Logger | None = None,
                     timeout_seconds: float = PROFILE_REFRESH_WAIT_SECONDS,
                     baseline_summary: Optional[str] = None) -> Optional[str]:
    """
    轮询直到 memory_profiles.summary_text 长度 >= min_len 且（若给了 baseline）与 baseline 不同。
    返回最终的 summary_text，超时返回 None。
    """
    deadline = time.monotonic() + timeout_seconds
    last: Optional[str] = None
    while time.monotonic() < deadline:
        rows = db.query(
            f"SELECT COALESCE(summary_text, '') FROM memory_profiles "
            f"WHERE user_id = {user_id} AND character_id = {character_id}"
        )
        if rows:
            last = rows[0][0]
            if last and len(last) >= min_len:
                if baseline_summary is None or last != baseline_summary:
                    return last
        time.sleep(1.0)
    return last


async def run_profile(token: str, db: DbHandle, user_id: int, character_id: int,
                      log: Logger) -> ScenarioResult:
    """
    场景 1+2+3 合并：
    - 1) 抽取：5 句包含可记忆事实的消息后，profile.summary_text 应含至少 3 个 fact
    - 2) 偏好使用：再问一句让 AI 主动用画像，AI 回复应不掉链子（这条做软断言）
    - 3) 自更新：用户改变设定后，下一次 profile 刷新应反映新事实

    Plan 2.7 时序保留断言：profile 抽取异步进行，主对话本身不应阻塞。
    """
    log.info("==> [scenario] profile (抽取 + 偏好使用 + 自更新)")

    # 清掉之前可能存在的节流 key，让本次抽取立刻可触发
    clear_profile_throttle(user_id, character_id)

    t0 = time.monotonic()
    # 发前 N-1 条消息让 fact 累积在消息表里；每条之间不清节流 key（模拟真实用户行为）
    # 最后再清一次节流 + 发"总结性"那条作为 trigger，让 listener 用最近 10 条做抽取
    initial_msgs = PROFILE_EXTRACT_MESSAGES[:-1]
    replies = await chat(token, initial_msgs, log)
    chat_elapsed = time.monotonic() - t0

    # 时序断言：主对话本身（4 条 user 消息 + AI 回复）应在合理时长内
    # 这里只确认 AI 给了回复，不卡死 —— Plan 2.7 时序保留要求 listener 异步执行
    ai_replies_total = sum(len(r.new_messages) for r in replies)
    log.debug(f"profile 阶段：{len(initial_msgs)} 条 user 消息耗时 {chat_elapsed:.1f}s，AI 总气泡数 {ai_replies_total}")
    if ai_replies_total == 0:
        return ScenarioResult("profile", "FAIL", "AI 没产生任何回复气泡，主对话链路异常")

    # 清节流 → 发最后一条 trigger 消息让 listener 用最近 10 条做一次抽取
    # （这时最近 10 条里大概率包含了 4 条 user fact + 几条 AI 回复）
    clear_profile_throttle(user_id, character_id)
    await chat(token, [PROFILE_EXTRACT_MESSAGES[-1]], log)

    # 等异步 profile listener 落库
    log.info("    等 profile 异步落库（最多 30s）...")
    summary_after_extract = wait_for_profile(db, user_id, character_id, min_len=30, log=log)
    if not summary_after_extract:
        return ScenarioResult(
            "profile_extract", "FAIL",
            "memory_profiles 表 30s 内没有出现长度 >= 30 的 summary_text"
        )

    log.debug(f"profile.summary_text (after extract): {summary_after_extract!r}")
    # 软断言：summary 里至少要出现以下关键事实里的 3 个
    facts = ["张三", "28", "前端", "橘子", "北京", "杭州", "诺兰", "羽毛球"]
    hit = [f for f in facts if f in summary_after_extract]
    if len(hit) < 3:
        return ScenarioResult(
            "profile_extract", "FAIL",
            f"summary_text 只命中 {len(hit)}/8 个关键事实: {hit}（要求 >= 3）"
        )

    # 子断言 2：偏好使用 —— 问 AI 一个能调用画像的问题
    clear_profile_throttle(user_id, character_id)
    log.info("    [profile_use] 问 AI 一个调用画像的问题")
    use_replies = await chat(token, ["你还记得我家那只猫的名字吗？"], log)
    use_text = use_replies[0].ai_concat
    log.debug(f"profile_use AI 回复: {use_text!r}")
    use_pass = "橘子" in use_text or "猫" in use_text  # LLM 不一定吐"橘子"原词，软判定

    # 子断言 3：自更新 —— 改变设定后再次触发抽取
    clear_profile_throttle(user_id, character_id)
    log.info("    [profile_update] 用户改变设定（从前端转产品经理）")
    update_msgs = [
        "其实跟你说一下，我已经不做前端了，上周正式转到产品经理岗位了。",
        "现在主要负责一个 AI 陪伴类的产品规划。",
    ]
    await chat(token, update_msgs, log)

    log.info("    等 profile 更新落库（最多 30s）...")
    summary_after_update = wait_for_profile(
        db, user_id, character_id, min_len=30, log=log,
        baseline_summary=summary_after_extract,
    )
    if not summary_after_update or summary_after_update == summary_after_extract:
        return ScenarioResult(
            "profile_update", "FAIL",
            "profile.summary_text 在用户改变设定 30s 后没有任何变化"
        )

    log.debug(f"profile.summary_text (after update): {summary_after_update!r}")
    # 软断言：新 summary 应反映 PM / 产品 / AI 陪伴 等关键信号之一
    update_signals = ["产品经理", "产品", "PM", "AI 陪伴", "AI陪伴", "转岗"]
    update_hit = [s for s in update_signals if s in summary_after_update]

    # 汇总
    detail = (
        f"extract 命中 {len(hit)}/8 facts {hit}; "
        f"use {'PASS' if use_pass else 'SOFT-FAIL'}; "
        f"update 命中 {len(update_hit)} 信号 {update_hit}"
    )
    if len(hit) >= 3 and len(update_hit) >= 1:
        return ScenarioResult("profile", "PASS", detail)
    else:
        return ScenarioResult("profile", "FAIL", detail)


# ----------------------------- 场景 F: throttle -----------------------------

async def run_throttle(token: str, db: DbHandle, user_id: int, character_id: int,
                       log: Logger) -> ScenarioResult:
    """
    场景 F: 5 分钟节流验证。
    流程：
    1. 清节流 key
    2. 发一条消息触发 profile 抽取
    3. 等节流 key 被 SET（应该立刻有）
    4. 立刻再发一条消息，profile 应该不再被刷新（version 不增）
    5. 删节流 key，再发一条消息，profile 应该刷新（version 增）
    """
    log.info("==> [scenario] throttle (5 分钟节流)")

    clear_profile_throttle(user_id, character_id)

    log.info("    [1/3] 触发首次 profile 抽取")
    await chat(token, ["我最喜欢的食物是火锅，特别是潮汕牛肉锅。"], log)

    log.info("    等首次 profile 落库...")
    summary1 = wait_for_profile(db, user_id, character_id, min_len=10, log=log)
    if summary1 is None:
        return ScenarioResult("throttle", "FAIL", "首次 profile 抽取没落库")

    # 拿 version
    rows = db.query(
        f"SELECT version FROM memory_profiles WHERE user_id = {user_id} AND character_id = {character_id}"
    )
    version1 = int(rows[0][0]) if rows else None
    if version1 is None:
        return ScenarioResult("throttle", "FAIL", "memory_profiles 表没有记录")
    log.debug(f"version1 = {version1}")

    # 验证 Redis 节流 key 存在
    ttl = redis_cmd("TTL", f"{THROTTLE_KEY_PREFIX}{user_id}:{character_id}")
    if not ttl.isdigit() or int(ttl) <= 0:
        return ScenarioResult(
            "throttle", "FAIL",
            f"profile 触发后节流 key TTL 异常: {ttl} (期望 > 0)"
        )
    log.debug(f"节流 key TTL = {ttl}s (期望 ~{PROFILE_EXTRACT_THROTTLE_MINUTES*60}s)")
    if int(ttl) < 60:
        log.warn(f"TTL ({ttl}s) 比预期 ({PROFILE_EXTRACT_THROTTLE_MINUTES*60}s) 小很多，可能 hit 已经走过一半")

    log.info("    [2/3] 节流期内再发一条（profile 应不被刷新）")
    await chat(token, ["还有日料也很喜欢，刺身和寿司都好。"], log)
    time.sleep(10)  # 给 listener 一点时间运行，但因节流应当跳过
    rows = db.query(
        f"SELECT version FROM memory_profiles WHERE user_id = {user_id} AND character_id = {character_id}"
    )
    version2 = int(rows[0][0]) if rows else None
    if version2 != version1:
        return ScenarioResult(
            "throttle", "FAIL",
            f"节流期内 profile version 变了 ({version1} -> {version2}), 节流失效"
        )
    log.debug(f"version2 = {version2} (未变, 节流生效 ✓)")

    log.info("    [3/3] 手动清节流 key → 下一次消息应该再次刷新 profile")
    clear_profile_throttle(user_id, character_id)
    await chat(token, ["还有我特别爱吃辣，川菜湘菜都行。"], log)

    log.info("    等第二次 profile 刷新落库...")
    deadline = time.monotonic() + PROFILE_REFRESH_WAIT_SECONDS
    version3 = version2
    while time.monotonic() < deadline:
        rows = db.query(
            f"SELECT version FROM memory_profiles WHERE user_id = {user_id} AND character_id = {character_id}"
        )
        version3 = int(rows[0][0]) if rows else version2
        if version3 > version2:
            break
        time.sleep(1.0)

    if version3 <= version2:
        return ScenarioResult(
            "throttle", "FAIL",
            f"清节流后 profile 仍未刷新 (version2={version2}, version3={version3})"
        )

    return ScenarioResult(
        "throttle", "PASS",
        f"节流生效 (v{version1}=v{version2}); 清 key 后恢复刷新 (v{version2} -> v{version3})"
    )


# ----------------------------- 场景 G: summary -----------------------------

async def run_summary(token: str, db: DbHandle, user_id: int, character_id: int,
                      log: Logger) -> ScenarioResult:
    """
    场景 G: 30 条消息触发 summary + S4 summary 回忆。

    需要 user_msg + AI_msg >= 30 条才触发一次 summary。我们发 16 条 user 消息，
    AI 每条若回 1-2 条，加起来应稳定超过 30 条。

    然后单独发一条 trigger 验证 AI 能调用 summary 回忆早期对话。
    """
    log.info("==> [scenario] summary (30 条阈值触发)")

    # 先看现在有多少条
    rows = db.query(
        f"SELECT COUNT(*) FROM message WHERE user_id = {user_id}"
    )
    msg_count_before = int(rows[0][0]) if rows else 0
    rows = db.query(
        f"SELECT COUNT(*) FROM memory_summaries WHERE user_id = {user_id} AND character_id = {character_id}"
    )
    summary_count_before = int(rows[0][0]) if rows else 0
    log.debug(f"summary 测试前: message={msg_count_before}, summaries={summary_count_before}")

    # 发 16 条 user 消息（题材多样，便于 summary 抽不同主题）
    msgs = [
        "今天上午跟产品评审了一版改版方案。",
        "晚饭打算吃日料，最近迷上了金枪鱼大腩。",
        "在看《沙丘》原著，弗兰克·赫伯特真神了。",
        "周末打算去爬香山，有人推荐路线吗。",
        "最近迷上了煮咖啡，搞了一个 V60 滤杯。",
        "上周去看了北京国安主场，输了好可惜。",
        "我家那只橘子最近爱在床头睡，超暖。",
        "在学吉他，最近练 fingerstyle，难度不小。",
        "想换工作了，正在面 PM 岗。",
        "昨晚跑步 10 公里，配速 5'30，挺累的。",
        "最近开始看心理学，强力推荐《被讨厌的勇气》。",
        "想买一台烤箱，主要烤蛋糕和欧包。",
        "种了几盆绿植，月季和薄荷活得最好。",
        "周末计划去看朋友的乐队 live。",
        "想报个潜水课，三亚或菲律宾都行。",
        "明年想去日本旅行，最想看富士山。",
    ]
    log.info(f"    发 {len(msgs)} 条 user 消息...")
    replies = await chat(token, msgs, log, wait_between=0.3)
    ai_reply_total = sum(len(r.new_messages) for r in replies)
    log.debug(f"AI 总气泡数: {ai_reply_total}")

    rows = db.query(
        f"SELECT COUNT(*) FROM message WHERE user_id = {user_id}"
    )
    msg_count_after = int(rows[0][0]) if rows else 0
    new_msg_count = msg_count_after - msg_count_before
    log.debug(f"新增 message 条数: {new_msg_count}")

    if new_msg_count < SUMMARY_TRIGGER_THRESHOLD:
        return ScenarioResult(
            "summary", "SKIP",
            f"新增消息只有 {new_msg_count} 条，不到 summary 触发阈值 {SUMMARY_TRIGGER_THRESHOLD}（AI 回复偏少）"
        )

    log.info(f"    等 summary 异步落库（最多 {SUMMARY_REFRESH_WAIT_SECONDS:.0f}s）...")
    deadline = time.monotonic() + SUMMARY_REFRESH_WAIT_SECONDS
    summary_count_after = summary_count_before
    while time.monotonic() < deadline:
        rows = db.query(
            f"SELECT COUNT(*) FROM memory_summaries WHERE user_id = {user_id} AND character_id = {character_id}"
        )
        summary_count_after = int(rows[0][0]) if rows else 0
        if summary_count_after > summary_count_before:
            break
        time.sleep(1.5)

    if summary_count_after <= summary_count_before:
        return ScenarioResult(
            "summary", "FAIL",
            f"30+ 条消息后 summary 表没有新记录 (before={summary_count_before}, after={summary_count_after})"
        )

    # 拿最新的 summary
    rows = db.query(
        f"SELECT summary_text, message_count FROM memory_summaries "
        f"WHERE user_id = {user_id} AND character_id = {character_id} "
        f"ORDER BY created_at DESC LIMIT 1"
    )
    if not rows:
        return ScenarioResult("summary", "FAIL", "summary 表查询不到记录")
    latest_summary, msg_cnt = rows[0]
    log.debug(f"最新 summary (msg_count={msg_cnt}): {latest_summary!r}")
    if not latest_summary or len(latest_summary) < 20:
        return ScenarioResult(
            "summary", "FAIL",
            f"summary_text 太短 (len={len(latest_summary)}), 内容: {latest_summary!r}"
        )

    # S4 验证：发一条问题让 AI 调用 summary 里的远期事实
    log.info("    [S4] 让 AI 调用 summary 回忆早期话题")
    trigger_replies = await chat(token, ["你还记得我之前提到过想去看哪个山吗？"], log)
    trigger_text = trigger_replies[0].ai_concat
    log.debug(f"S4 AI 回复: {trigger_text!r}")
    s4_pass = "香山" in trigger_text or "富士" in trigger_text or "山" in trigger_text

    detail = (
        f"new_msgs={new_msg_count}, summaries +{summary_count_after - summary_count_before}; "
        f"S4 {'PASS' if s4_pass else 'SOFT-FAIL'} (AI 回复含'山': {s4_pass})"
    )
    # 只要 summary 落库就算 PASS，S4 是软断言
    return ScenarioResult("summary", "PASS" if s4_pass else "PASS", detail)


# ----------------------------- 场景 S5: RAG -----------------------------

async def run_rag(token: str, db: DbHandle, user_id: int, character_id: int,
                  log: Logger) -> ScenarioResult:
    """
    场景 S5: RAG 跨时段召回。

    需要先累积足够多消息触发 chunk 切片 + embedding 入库（RAG_CHUNK_MIN_SIZE=5）。
    然后发一条"远期"问题，验证 chat_embeddings 表有该用户的索引行；
    AI 回复里能命中远期 chunk 里的关键词算 PASS。
    """
    log.info("==> [scenario] rag (跨时段召回)")

    # 看当前已索引的 chunk 数
    rows = db.query(
        f"SELECT COUNT(*) FROM chat_embeddings WHERE user_id = {user_id} AND character_id = {character_id}"
    )
    chunk_count_before = int(rows[0][0]) if rows else 0
    log.debug(f"RAG 测试前 chat_embeddings 数量: {chunk_count_before}")

    # 故意聊一段"远期独特"主题，让 RAG 必须召回
    rag_msgs = [
        "周五我去三里屯一家叫『小神龟』的酒馆喝了一杯。",
        "他们家招牌是樱花气泡酒。",
        "那天碰到一只白色的拉布拉多，特别乖。",
        "酒馆老板娘叫念念，超会聊天。",
        "下次想约朋友们一起去那边吃饭。",
        "顺便说一下，我那只橘子最近爱蹲窗台。",
        "最近也在练阿根廷探戈。",
        "周日晚上要去看一场迷笛音乐节。",
    ]
    log.info(f"    [1/2] 发 {len(rag_msgs)} 条远期独特主题消息")
    await chat(token, rag_msgs, log, wait_between=0.3)

    # 等 RAG worker（@Scheduled fixedDelay=10000）异步处理 queue → 索引
    log.info(f"    等 RAG 索引落库（最多 {RAG_INDEX_WAIT_SECONDS:.0f}s）...")
    deadline = time.monotonic() + RAG_INDEX_WAIT_SECONDS
    chunk_count_after = chunk_count_before
    while time.monotonic() < deadline:
        rows = db.query(
            f"SELECT COUNT(*) FROM chat_embeddings WHERE user_id = {user_id} AND character_id = {character_id}"
        )
        chunk_count_after = int(rows[0][0]) if rows else 0
        if chunk_count_after > chunk_count_before:
            break
        time.sleep(1.5)

    if chunk_count_after <= chunk_count_before:
        return ScenarioResult(
            "rag", "SKIP",
            f"chat_embeddings 30s 内没有新索引 (before={chunk_count_before}, after={chunk_count_after}); "
            "可能 chunk 还没达到 RAG_CHUNK_MIN_SIZE 或 RagIndexWorker 未运行"
        )

    # 中间发一些 noise 消息，避开"刚说过"短期窗口
    noise_msgs = [
        "今天天气真好。",
        "我刚回家，超累。",
        "晚上想吃面。",
        "你说我换什么背景图好。",
    ]
    log.info("    [noise] 发几条无关消息，远离远期 chunk")
    await chat(token, noise_msgs, log, wait_between=0.2)

    # 发远期 trigger
    log.info("    [2/2] 远期 trigger: 问那家酒馆")
    trig_replies = await chat(token, ["我之前提过的那家酒馆，老板娘叫什么来着？"], log)
    text = trig_replies[0].ai_concat
    log.debug(f"RAG trigger AI 回复: {text!r}")

    keywords = ["小神龟", "念念", "酒馆", "三里屯", "樱花"]
    hit = [k for k in keywords if k in text]
    rag_pass = len(hit) >= 1

    detail = (
        f"chunks +{chunk_count_after - chunk_count_before}; "
        f"AI 回复命中关键词: {hit}"
    )
    return ScenarioResult("rag", "PASS" if rag_pass else "FAIL", detail)


# 注：原"场景 E: degradation"（ssh old 停 sanyan-embedding 服务验证降级）已废弃——
# 2026-05-17 embedding 改用硅基流动 API 后，老服务器上的 sanyan-embedding 服务已下线，
# 该场景永远跑不通。embedding 不可用时的降级路径仍由
# MemoryRagSearchServiceTest（两条 fallback case）单元测试覆盖。


# ----------------------------- main runner -----------------------------

SCENARIO_REGISTRY: dict[str, Callable] = {
    "profile": run_profile,
    "throttle": run_throttle,
    "summary": run_summary,
    "rag": run_rag,
}

SCENARIO_ORDER = ["profile", "throttle", "summary", "rag"]

# 每个场景独立 user_id（≥ 900 避免跟真实用户撞），并行跑时数据按 user_id 隔离不冲突
SCENARIO_USER_IDS = {
    "profile": 901,
    "throttle": 902,
    "summary": 903,
    "rag": 904,
}


def _prefix_logger(base: Logger, prefix: str) -> Logger:
    """给 log 前缀加 scenario 名，并行跑时 4 路日志交错不至于混乱。"""
    wrapped = Logger(verbose=base.verbose)
    wrapped.info = lambda msg: base.info(f"[{prefix}] {msg}")
    wrapped.debug = lambda msg: base.debug(f"[{prefix}] {msg}")
    wrapped.warn = lambda msg: base.warn(f"[{prefix}] {msg}")
    return wrapped


async def _run_one_scenario(
    name: str, fn: Callable, token: str, db: DbHandle,
    user_id: int, character_id: int, log: Logger,
) -> ScenarioResult:
    """统一 wrap 一个 scenario：异常转 FAIL，加 log 前缀。"""
    sublog = _prefix_logger(log, name)
    sublog.info(f"================ 开始 ================")
    try:
        return await fn(token, db, user_id, character_id, sublog)
    except Exception as e:
        sublog.warn(f"抛异常: {e!r}")
        return ScenarioResult(name, "FAIL", f"scenario 抛异常: {e!r}")


async def main_async(args: argparse.Namespace) -> int:
    log = Logger(verbose=args.verbose)

    # 加载 env / 建 DB handle
    env = read_env()
    jwt_secret = env.get("JWT_SECRET")
    if not jwt_secret:
        log.warn("JWT_SECRET 未配置在 .env，回退到 application.yml 默认值（你大概率没改默认）")
        jwt_secret = "your-256-bit-secret-key-for-sanyan-app-change-in-production"
    pg_user = env.get("PG_USER", "sanyan")
    pg_password = env.get("PG_PASSWORD", "")
    db = DbHandle(pg_user=pg_user, pg_password=pg_password)

    # 决定跑哪些场景
    if args.scenario == "all":
        scenarios = SCENARIO_ORDER
    else:
        if args.scenario not in SCENARIO_REGISTRY:
            log.info(f"未知 scenario: {args.scenario}")
            return 2
        scenarios = [args.scenario]

    # 计算每个场景的 user_id：--user-id 显式指定则所有场景共用（兼容旧用法 + 调试单场景）；
    # 否则用 SCENARIO_USER_IDS 隔离区（每个场景独立 user_id，避免污染真实用户 + 支持并行）
    use_isolated_users = (args.user_id is None)
    scenario_uids = {
        name: (SCENARIO_USER_IDS[name] if use_isolated_users else args.user_id)
        for name in scenarios
    }
    # 一个进程里所有场景共用 character_id
    character_id = args.character_id

    # 清理（默认 true，--no-clean 跳过）—— 每个场景的 user_id 各清一次
    if args.clean:
        unique_uids = sorted(set(scenario_uids.values()))
        log.info(f"==> [clean] 清理 user_ids={unique_uids} 的测试数据")
        for uid in unique_uids:
            clean_test_data(db, uid, log)

    # 执行：并行 vs 串行
    if args.parallel and len(scenarios) > 1:
        log.info(f"==> 并行跑 {len(scenarios)} 个场景: {scenarios}")
        tasks = [
            _run_one_scenario(
                name, SCENARIO_REGISTRY[name],
                mint_jwt(jwt_secret, scenario_uids[name]),
                db, scenario_uids[name], character_id, log,
            )
            for name in scenarios
        ]
        results = await asyncio.gather(*tasks, return_exceptions=False)
    else:
        log.info(f"==> 串行跑 {len(scenarios)} 个场景: {scenarios}")
        results = []
        for name in scenarios:
            r = await _run_one_scenario(
                name, SCENARIO_REGISTRY[name],
                mint_jwt(jwt_secret, scenario_uids[name]),
                db, scenario_uids[name], character_id, log,
            )
            results.append(r)
            log.info(r.line())

    # 打印汇总
    log.info("")
    log.info("================ 汇总 ================")
    for r in results:
        log.info(r.line())
    total = len(results)
    passed = sum(1 for r in results if r.status == "PASS")
    failed = sum(1 for r in results if r.status == "FAIL")
    skipped = sum(1 for r in results if r.status == "SKIP")
    log.info("")
    log.info(f"Total: {passed}/{total} PASS, {failed} FAIL, {skipped} SKIP")

    return 0 if failed == 0 else 1


def build_argparser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description="Plan 2 dogfood E2E 测试 harness (运行在 new 服务器本地)"
    )
    p.add_argument(
        "--scenario", default="all",
        choices=["all", *SCENARIO_ORDER],
        help="要跑的场景, all = 全部按顺序跑"
    )
    p.add_argument(
        "--clean", dest="clean", action="store_true", default=True,
        help="跑前清掉 user_id 的 message/memory_* 表 + Redis 节流 key（默认开）"
    )
    p.add_argument(
        "--no-clean", dest="clean", action="store_false",
        help="跳过清理，接着现有数据继续测"
    )
    p.add_argument(
        "--user-id", type=int, default=None,
        help="测试用户 id；默认 None → 各场景用 SCENARIO_USER_IDS 隔离区 user_id（901-904）。"
             "显式指定（如 --user-id 1）则所有场景共用，方便调试单场景。"
    )
    p.add_argument("--character-id", type=int, default=1, help="角色 id（默认 1，小婉）")
    p.add_argument(
        "--parallel", dest="parallel", action="store_true", default=True,
        help="并行跑多场景（默认开，asyncio.gather）"
    )
    p.add_argument(
        "--sequential", dest="parallel", action="store_false",
        help="串行跑（调试 / 日志可读性强）"
    )
    p.add_argument("-v", "--verbose", action="store_true",
                   help="详细 log（含每条消息发送和收到的 AI 回复）")
    return p


def main() -> None:
    parser = build_argparser()
    args = parser.parse_args()
    code = asyncio.run(main_async(args))
    sys.exit(code)


if __name__ == "__main__":
    main()
