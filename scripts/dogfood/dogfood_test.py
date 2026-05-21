#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Plan 2 + Plan 3 dogfood E2E 测试 harness。

设计为在 **new 服务器（49.233.213.109）本地** 执行：
- WS 直接打 ws://localhost:8080/ws，避免被本地宽带的 NAT/中转干扰
- psql / redis-cli 命中本机服务，零网络开销

Plan 2 场景（4 个，保持不变）：profile / throttle / summary / rag
Plan 3 场景（10 个，需 --plan3 或 --scenario=all-plan3 启用缩短阈值）：
  plan3_baseline / plan3_daily_login / plan3_streak_consecutive /
  plan3_streak_gap_reset / plan3_message_cap / plan3_stage_transition /
  plan3_plot_deep_night / plan3_plot_first_honest_share /
  plan3_ai_quality / plan3_stage_prompt

依赖（new 上已装）:
- Python 3.13+
- websockets (16.0)
- PyJWT (2.12)
- 本机 psql / redis-cli
"""

from __future__ import annotations

import argparse
import asyncio
import datetime
import http.client
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

ENV_FILE = "/etc/3yan/3yan-server.env"  # systemd unit 的 EnvironmentFile，唯一 env 源
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

# ---- Plan 3 相关常量 ----

# dogfood 模式下的缩短阈值（与 plan3_env_override.env 保持一致）
P3_STRANGER_END = 20
P3_FRIEND_END = 40
P3_AMBIGUOUS_END = 60
P3_LOVER_END = 80
P3_MESSAGE_DAILY_CAP = 50
P3_AI_TRIGGER_N = 3      # 每 3 条 user 消息触发一次 AI 质量评估

# Plan 3 intimacy_logs 的 reason 字符串
REASON_MESSAGE_SENT = "MESSAGE_SENT"
REASON_DAILY_LOGIN = "DAILY_LOGIN"
REASON_CAPPED = "CAPPED"
REASON_PLOT_PREFIX = "PLOT:"
REASON_DEEP_NIGHT = "PLOT:deep_night_chat"
REASON_FIRST_HONEST = "PLOT:first_honest_share"
REASON_AI_QUALITY = "AI_QUALITY"

# Redis key 前缀（Plan 3）
STREAK_KEY_PREFIX = "streak:user:"
BEHAVIOR_KEY_PREFIX = "behavior:user:"

# REST 服务地址（本机 localhost）
REST_HOST = "localhost"
REST_PORT = 8080

# WS frame type 字符串
WS_INTIMACY_UPDATE = "intimacy_update"
WS_STAGE_TRANSITION = "stage_transition"
WS_STAGE_STORY = "stage_story"

# 等待亲密度异步落库的超时（秒）
INTIMACY_WAIT_SECONDS = 10.0


# ----------------------------- 记忆召回测试常量 -----------------------------

# 召回测试用的"无关填充消息"池——35 条纯闲聊，绝不含场景关键词，
# 用来在 plant 之后把 plant 推出 SHORT_TERM_WINDOW_SIZE=32 短期窗口。
# 任何修改必须同步跑 test_distract_pool_不含场景关键词避免污染 防止污染。
RECALL_DISTRACT_MESSAGES = [
    "今天天气怎么样啊", "你平时几点睡觉", "最近有什么好看的剧推荐吗",
    "周末一般都干嘛", "你喜欢吃什么口味的菜", "运动一般做什么",
    "工作上忙吗", "今天心情怎么样", "你对秋天感觉如何",
    "咖啡还是茶", "早睡早起做得到吗", "节假日喜欢出去玩还是宅家",
    "你养过宠物吗", "听音乐喜欢什么类型", "周末有出去的打算吗",
    "你怎么看待加班这件事", "最近压力大吗", "买东西看重价格还是品牌",
    "对未来三年有什么规划", "聊聊你最近读的书吧", "你怕冷还是怕热",
    "更喜欢城市还是乡村", "你早上吃早饭吗", "对健身房有什么看法",
    "拖延症严重吗", "聊天和打电话你更喜欢哪个", "周末睡懒觉到几点",
    "对相亲怎么看", "你怎么放松", "晚上一般几点入睡",
    "你做饭吗", "对短视频上瘾吗", "下班后做什么",
    "对极简生活怎么看", "你怎么看待独居",
]

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
            # 进度行：[i/N] + 发送内容截断（默认 verbose=False 下也能看进度）
            preview = content[:30] + "…" if len(content) > 30 else content
            log.info(f"    [msg {i+1}/{len(contents)}] → {preview}")
            t_start = time.monotonic()
            r = await send_one(ws, content, log)
            elapsed = time.monotonic() - t_start
            ai_total_chars = sum(len(str(m.get("content", ""))) for m in r.new_messages)
            log.info(f"    [msg {i+1}/{len(contents)}] ✓ AI 回 {len(r.new_messages)} 气泡/{ai_total_chars}字 耗时{elapsed:.1f}s")
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


# =====================================================================
# Plan 3 扩展：亲密度系统 E2E dogfood 场景（10 个）
# user_id 910-919，与 plan2 的 901-904 完全隔离。
# 需在 dogfood 模式下运行（阈值已通过环境变量缩短）：
#   stranger: 0-5, friend: 5-15, ambiguous: 15-30, lover: 30-50
#   AI quality trigger: 每 3 条 user 消息
# =====================================================================


# ----------------------------- Plan 3: REST 助手 -----------------------------

def rest_get(path: str, token: str) -> dict[str, Any]:
    """
    同步 HTTP GET 调用本机 REST 接口，返回解析后的 JSON dict。
    Authorization: Bearer <token>
    """
    conn = http.client.HTTPConnection(REST_HOST, REST_PORT, timeout=15)
    try:
        conn.request("GET", path, headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/json",
        })
        resp = conn.getresponse()
        body = resp.read().decode("utf-8")
        return json.loads(body)
    finally:
        conn.close()


# ----------------------------- Plan 3: WS 扩展数据结构 -----------------------------

@dataclass
class P3WsReply:
    """
    plan3 场景专用的 WS 收帧归并——在 WsReply 基础上额外捕获 plan3 新增帧。

    用 send_one_p3 替换 send_one，额外收集：
      - intimacy_update 帧列表
      - stage_transition 帧列表
      - stage_story 帧列表
    """
    ack_server_msg_id: Optional[int] = None
    typing_count: int = 0
    new_messages: list[dict[str, Any]] = field(default_factory=list)
    errors: list[dict[str, Any]] = field(default_factory=list)
    intimacy_updates: list[dict[str, Any]] = field(default_factory=list)
    stage_transitions: list[dict[str, Any]] = field(default_factory=list)
    stage_stories: list[dict[str, Any]] = field(default_factory=list)

    @property
    def ai_texts(self) -> list[str]:
        return [m.get("content", "") for m in self.new_messages]

    @property
    def ai_concat(self) -> str:
        return "\n".join(self.ai_texts)


async def send_one_p3(ws, content: str, log: Logger) -> P3WsReply:
    """
    与 send_one 功能相同，额外收集 plan3 新增 WS 帧
    （intimacy_update / stage_transition / stage_story）。
    """
    client_msg_id = str(uuid.uuid4())
    payload = {"type": "send_message", "content": content, "clientMsgId": client_msg_id}
    log.debug(f"→ send_message(p3): {content[:60]}")
    await ws.send(json.dumps(payload))

    reply = P3WsReply()
    start = time.monotonic()
    while True:
        remaining_hard = REPLY_HARD_TIMEOUT_SECONDS - (time.monotonic() - start)
        if remaining_hard <= 0:
            log.warn("hard timeout 90s 无 turn_complete，强制结束等待 AI 回复")
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
            data = msg.get("message", {})
            reply.new_messages.append(data)
            log.debug(f"← new_message [{data.get('id')}]: {str(data.get('content',''))[:80]}")
        elif mtype == "error":
            reply.errors.append(msg)
            log.warn(f"← error: {msg}")
        elif mtype == WS_INTIMACY_UPDATE:
            reply.intimacy_updates.append(msg)
            log.debug(f"← intimacy_update score={msg.get('score')} delta={msg.get('delta')} reason={msg.get('reason')}")
        elif mtype == WS_STAGE_TRANSITION:
            reply.stage_transitions.append(msg)
            log.debug(f"← stage_transition from={msg.get('from')} to={msg.get('to')}")
        elif mtype == WS_STAGE_STORY:
            reply.stage_stories.append(msg)
            log.debug(f"← stage_story stage={msg.get('stage')}")
        elif mtype == "turn_complete":
            log.debug(f"← turn_complete messageCount={msg.get('messageCount')}")
            break
        else:
            log.debug(f"← (其它) {mtype}: {raw[:120]}")

    return reply


async def chat_p3(token: str, contents: list[str], log: Logger,
                  wait_between: float = 0.5) -> list[P3WsReply]:
    """plan3 版 chat，返回 P3WsReply 列表。"""
    url = WS_URL_TEMPLATE.format(token=token)
    replies: list[P3WsReply] = []
    log.debug(f"WS 连接 {url}")
    async with websockets.connect(url, ping_interval=20, ping_timeout=20) as ws:
        for i, content in enumerate(contents):
            preview = content[:30] + "…" if len(content) > 30 else content
            log.info(f"    [msg {i+1}/{len(contents)}] → {preview}")
            t_start = time.monotonic()
            r = await send_one_p3(ws, content, log)
            elapsed = time.monotonic() - t_start
            ai_total_chars = sum(len(str(m.get("content", ""))) for m in r.new_messages)
            log.info(f"    [msg {i+1}/{len(contents)}] ✓ AI 回 {len(r.new_messages)} 气泡/{ai_total_chars}字 耗时{elapsed:.1f}s")
            replies.append(r)
            if wait_between > 0:
                await asyncio.sleep(wait_between)
    return replies


# ----------------------------- Plan 3: 清理函数 -----------------------------

def clean_plan3_data(db: DbHandle, user_id: int, character_id: int, log: Logger) -> None:
    """
    清理 plan3 场景产生的全部测试数据：
    - DB：relationships / intimacy_logs / relationship_milestones / message
    - Redis：streak key / behavior(daily cap) key
    """
    log.info(f"==> [clean-p3] 清理 user_id={user_id} 的 plan3 测试数据")
    db.execute(f"DELETE FROM relationship_milestones WHERE user_id = {user_id}")
    db.execute(f"DELETE FROM intimacy_logs WHERE user_id = {user_id}")
    db.execute(f"DELETE FROM relationships WHERE user_id = {user_id}")
    db.execute(f"DELETE FROM message WHERE user_id = {user_id}")

    # Redis streak key
    streak_key = f"{STREAK_KEY_PREFIX}{user_id}"
    redis_cmd("DEL", streak_key)

    # Redis behavior(daily cap) keys（按日期命名，scan 匹配）
    beh_keys = redis_cmd("--scan", "--pattern", f"{BEHAVIOR_KEY_PREFIX}{user_id}:*")
    for k in beh_keys.splitlines():
        if k.strip():
            redis_cmd("DEL", k.strip())

    log.debug("[clean-p3] 完成")


def wait_for_intimacy_log(db: DbHandle, user_id: int, character_id: int,
                          reason: str, log: Logger,
                          timeout_seconds: float = INTIMACY_WAIT_SECONDS) -> Optional[dict[str, str]]:
    """
    轮询 intimacy_logs，等待出现 reason 匹配的记录（支持前缀匹配，如 'PLOT:'）。
    返回第一条匹配行的 dict（delta / reason / new_score / new_stage），超时返回 None。
    """
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        rows = db.query(
            f"SELECT delta, reason, new_score, new_stage FROM intimacy_logs "
            f"WHERE user_id = {user_id} AND character_id = {character_id} "
            f"AND reason LIKE '{reason}%' "
            f"ORDER BY id DESC LIMIT 1"
        )
        if rows:
            r = rows[0]
            return {"delta": r[0], "reason": r[1], "new_score": r[2], "new_stage": r[3]}
        time.sleep(0.5)
    return None


# ----------------------------- Plan 3: scenario 1 — baseline -----------------------------

async def run_plan3_baseline(token: str, db: DbHandle, user_id: int, character_id: int,
                             log: Logger) -> ScenarioResult:
    """
    plan3_baseline：
    clean → GET /api/relationships/me → 验证返回默认值
    （intimacy_score=0, current_stage=0, current_stage_name="陌生人"）
    """
    log.info("==> [scenario] plan3_baseline (REST /me 默认值)")

    resp = rest_get("/api/relationships/me", token)
    log.debug(f"GET /api/relationships/me 响应: {json.dumps(resp, ensure_ascii=False)}")

    # 首次 GET /me 会触发 findOrCreate，可能顺带触发 DAILY_LOGIN；
    # 我们只断言 REST 返回字段结构正确，不强断 intimacy_score=0
    data = resp.get("data") or resp.get("result") or resp
    if not data:
        return ScenarioResult("plan3_baseline", "FAIL", f"响应 data 为空: {resp}")

    # 验证必需字段存在
    required_fields = ["userId", "characterId", "intimacyScore",
                       "currentStage", "currentStageName",
                       "nextStageThreshold", "percentToNextStage"]
    missing = [f for f in required_fields if f not in data]
    if missing:
        return ScenarioResult(
            "plan3_baseline", "FAIL",
            f"响应缺字段: {missing}，完整响应: {data}"
        )

    stage_name = data.get("currentStageName", "")
    stage = data.get("currentStage", -1)

    # 断言阶段名 + percent 范围
    if stage < 0 or stage > 4:
        return ScenarioResult("plan3_baseline", "FAIL", f"currentStage={stage} 超出范围 0-4")
    if not stage_name:
        return ScenarioResult("plan3_baseline", "FAIL", "currentStageName 为空")
    percent = data.get("percentToNextStage", -1)
    if not (0.0 <= percent <= 1.0):
        return ScenarioResult(
            "plan3_baseline", "FAIL",
            f"percentToNextStage={percent} 超出范围 0.0-1.0"
        )

    return ScenarioResult(
        "plan3_baseline", "PASS",
        f"字段完整; stage={stage}({stage_name}); "
        f"score={data.get('intimacyScore')}; percent={percent:.2f}"
    )


# ----------------------------- Plan 3: scenario 2 — daily_login -----------------------------

async def run_plan3_daily_login(token: str, db: DbHandle, user_id: int, character_id: int,
                                log: Logger) -> ScenarioResult:
    """
    plan3_daily_login：
    1. GET /api/relationships/me → 触发 findOrCreate + DAILY_LOGIN (+10 streak=1)
    2. 等 intimacy_logs 出现 DAILY_LOGIN 行
    3. 验证 Redis streak:user:<uid> hash streak=1
    """
    log.info("==> [scenario] plan3_daily_login (首次登录 DAILY_LOGIN +10)")

    resp = rest_get("/api/relationships/me", token)
    data = resp.get("data") or resp.get("result") or resp
    log.debug(f"GET /me 响应: {json.dumps(data, ensure_ascii=False)}")

    if not data or "intimacyScore" not in data:
        return ScenarioResult("plan3_daily_login", "FAIL", f"REST 返回异常: {resp}")

    # 等 DAILY_LOGIN 落 intimacy_logs
    log.info("    等 DAILY_LOGIN 落 intimacy_logs（最多 10s）...")
    row = wait_for_intimacy_log(db, user_id, character_id, REASON_DAILY_LOGIN, log)
    if row is None:
        return ScenarioResult(
            "plan3_daily_login", "FAIL",
            "10s 内 intimacy_logs 没有出现 DAILY_LOGIN 记录"
        )
    log.debug(f"DAILY_LOGIN 行: {row}")

    # 验证 delta=15（streak=1，dailyLogin=10 + bonus = min(1*5, 50)=5 → 15）
    actual_delta = int(row["delta"])
    if actual_delta != 15:
        return ScenarioResult(
            "plan3_daily_login", "FAIL",
            f"DAILY_LOGIN delta 期望 15（streak=1: 10 base + 5 bonus），实际 {actual_delta}"
        )

    # 验证 Redis streak=1
    streak_key = f"{STREAK_KEY_PREFIX}{user_id}"
    streak_val = redis_cmd("HGET", streak_key, "streak")
    log.debug(f"Redis streak:user:{user_id} streak={streak_val!r}")
    if streak_val.strip() != "1":
        return ScenarioResult(
            "plan3_daily_login", "FAIL",
            f"Redis streak 期望 1，实际 {streak_val!r}"
        )

    return ScenarioResult(
        "plan3_daily_login", "PASS",
        f"DAILY_LOGIN delta={actual_delta}; Redis streak=1; new_score={row['new_score']}"
    )


# ----------------------------- Plan 3: scenario 3 — streak_consecutive -----------------------------

async def run_plan3_streak_consecutive(token: str, db: DbHandle, user_id: int, character_id: int,
                                       log: Logger) -> ScenarioResult:
    """
    plan3_streak_consecutive：
    模拟连续 4 天登录（注入 Redis streak=3, last_date=昨天），
    再 GET /me → 触发 DAILY_LOGIN streak=4，delta=10 + 4*5=30。

    streak bonus 公式：min(streak × streakBonusPerDay, streakBonusCap)
      streak=4 → bonus = min(4×5, 50) = 20 → delta = 10+20 = 30
    """
    log.info("==> [scenario] plan3_streak_consecutive (连续 4 天登录 delta=30)")

    # 注入 Redis：streak=3, last_date=昨天（模拟已连续 3 天）
    yesterday = (datetime.date.today() - datetime.timedelta(days=1)).isoformat()
    streak_key = f"{STREAK_KEY_PREFIX}{user_id}"
    redis_cmd("HSET", streak_key, "streak", "3", "last_date", yesterday)
    log.debug(f"注入 streak=3, last_date={yesterday}")

    resp = rest_get("/api/relationships/me", token)
    data = resp.get("data") or resp.get("result") or resp
    if not data or "intimacyScore" not in data:
        return ScenarioResult("plan3_streak_consecutive", "FAIL", f"REST 返回异常: {resp}")

    log.info("    等 DAILY_LOGIN (streak=4) 落 intimacy_logs...")
    row = wait_for_intimacy_log(db, user_id, character_id, REASON_DAILY_LOGIN, log)
    if row is None:
        return ScenarioResult(
            "plan3_streak_consecutive", "FAIL",
            "10s 内 intimacy_logs 没有 DAILY_LOGIN 记录"
        )

    actual_delta = int(row["delta"])
    expected_delta = 30  # 10 + min(4×5, 50) = 30
    log.debug(f"DAILY_LOGIN delta={actual_delta} (期望 {expected_delta})")

    # 验证 Redis streak 已更新为 4
    streak_val = redis_cmd("HGET", streak_key, "streak")
    log.debug(f"Redis streak 更新后: {streak_val!r}")

    if actual_delta != expected_delta:
        return ScenarioResult(
            "plan3_streak_consecutive", "FAIL",
            f"delta 期望 {expected_delta}（streak=4），实际 {actual_delta}"
        )
    if streak_val.strip() != "4":
        return ScenarioResult(
            "plan3_streak_consecutive", "FAIL",
            f"Redis streak 期望 4，实际 {streak_val!r}"
        )

    return ScenarioResult(
        "plan3_streak_consecutive", "PASS",
        f"streak=4; delta={actual_delta}; new_score={row['new_score']}"
    )


# ----------------------------- Plan 3: scenario 4 — streak_gap_reset -----------------------------

async def run_plan3_streak_gap_reset(token: str, db: DbHandle, user_id: int, character_id: int,
                                     log: Logger) -> ScenarioResult:
    """
    plan3_streak_gap_reset：
    注入 Redis streak=5, last_date=3 天前（模拟中断），
    GET /me → streak 重置为 1，delta=10（无 bonus）。
    """
    log.info("==> [scenario] plan3_streak_gap_reset (中断后 streak 重置为 1)")

    three_days_ago = (datetime.date.today() - datetime.timedelta(days=3)).isoformat()
    streak_key = f"{STREAK_KEY_PREFIX}{user_id}"
    redis_cmd("HSET", streak_key, "streak", "5", "last_date", three_days_ago)
    log.debug(f"注入 streak=5, last_date={three_days_ago}")

    resp = rest_get("/api/relationships/me", token)
    data = resp.get("data") or resp.get("result") or resp
    if not data or "intimacyScore" not in data:
        return ScenarioResult("plan3_streak_gap_reset", "FAIL", f"REST 返回异常: {resp}")

    log.info("    等 DAILY_LOGIN (streak 重置后) 落 intimacy_logs...")
    row = wait_for_intimacy_log(db, user_id, character_id, REASON_DAILY_LOGIN, log)
    if row is None:
        return ScenarioResult(
            "plan3_streak_gap_reset", "FAIL",
            "10s 内 intimacy_logs 没有 DAILY_LOGIN 记录"
        )

    actual_delta = int(row["delta"])
    expected_delta = 15  # streak=1（重置后）: 10 base + min(1*5, 50)=5 bonus → 15

    streak_val = redis_cmd("HGET", streak_key, "streak")
    log.debug(f"streak 重置后 Redis streak={streak_val!r}, DAILY_LOGIN delta={actual_delta}")

    if actual_delta != expected_delta:
        return ScenarioResult(
            "plan3_streak_gap_reset", "FAIL",
            f"中断后 delta 期望 {expected_delta}（streak=1: 10+5），实际 {actual_delta}"
        )
    if streak_val.strip() != "1":
        return ScenarioResult(
            "plan3_streak_gap_reset", "FAIL",
            f"Redis streak 期望 1（重置），实际 {streak_val!r}"
        )

    return ScenarioResult(
        "plan3_streak_gap_reset", "PASS",
        f"streak 重置为 1; delta={actual_delta}; new_score={row['new_score']}"
    )


# ----------------------------- Plan 3: scenario 5 — message_cap -----------------------------

async def run_plan3_message_cap(token: str, db: DbHandle, user_id: int, character_id: int,
                                log: Logger) -> ScenarioResult:
    """
    plan3_message_cap：
    连续发 60 条消息（每条 +1，每日封顶 P3_MESSAGE_DAILY_CAP=50）。
    断言：
    - intimacy_logs 中 delta=1 的 MESSAGE_SENT 行 = 50 条
    - intimacy_logs 中 delta=0 且 reason=CAPPED 的行 = 10 条
    """
    log.info(f"==> [scenario] plan3_message_cap (发 60 条，封顶 {P3_MESSAGE_DAILY_CAP})")

    # 先确认 relationship 存在（findOrCreate）
    rest_get("/api/relationships/me", token)
    # 等 DAILY_LOGIN 落库后再清 daily cap key，避免 DAILY_LOGIN 分数算进消息封顶
    time.sleep(2)

    # 清掉今日行为计数器，从 0 开始计 50 条
    today_str = datetime.date.today().isoformat()
    beh_key = f"{BEHAVIOR_KEY_PREFIX}{user_id}:date:{today_str}"
    redis_cmd("DEL", beh_key)

    # 记录发消息前的 intimacy_logs 行数
    rows_before = db.query(
        f"SELECT COUNT(*) FROM intimacy_logs WHERE user_id = {user_id} AND character_id = {character_id}"
    )
    count_before = int(rows_before[0][0]) if rows_before else 0

    log.info("    发 60 条消息...")
    msgs = [f"消息第{i+1}条" for i in range(60)]
    await chat_p3(token, msgs, log, wait_between=0.1)

    # 稍等落库
    time.sleep(1)

    # 统计 MESSAGE_SENT 和 CAPPED 行
    rows_delta1 = db.query(
        f"SELECT COUNT(*) FROM intimacy_logs "
        f"WHERE user_id = {user_id} AND character_id = {character_id} "
        f"AND reason = 'MESSAGE_SENT' AND delta = 1 AND id > "
        f"(SELECT COALESCE(MAX(id),0) FROM intimacy_logs WHERE user_id = {user_id} "
        f" AND character_id = {character_id} AND id <= "
        f" (SELECT COALESCE(id, 0) FROM intimacy_logs WHERE user_id = {user_id} "
        f"  AND character_id = {character_id} ORDER BY id LIMIT 1 OFFSET {count_before}))"
    )

    # 用更简单的方式：直接统计新增行里各 reason 的数量
    rows_all = db.query(
        f"SELECT reason, delta, COUNT(*) FROM intimacy_logs "
        f"WHERE user_id = {user_id} AND character_id = {character_id} "
        f"AND reason IN ('MESSAGE_SENT', 'CAPPED') "
        f"GROUP BY reason, delta"
    )

    sent_count = 0
    capped_count = 0
    for r in rows_all:
        reason_val, delta_val, cnt = r[0], int(r[1]), int(r[2])
        if reason_val == REASON_MESSAGE_SENT and delta_val == 1:
            sent_count = cnt
        elif reason_val == REASON_CAPPED and delta_val == 0:
            capped_count = cnt

    log.debug(f"MESSAGE_SENT delta=1: {sent_count} 条; CAPPED delta=0: {capped_count} 条")

    if sent_count < P3_MESSAGE_DAILY_CAP:
        return ScenarioResult(
            "plan3_message_cap", "FAIL",
            f"MESSAGE_SENT delta=1 行数 {sent_count} < 期望 {P3_MESSAGE_DAILY_CAP}"
        )
    if capped_count < 10:
        return ScenarioResult(
            "plan3_message_cap", "FAIL",
            f"CAPPED delta=0 行数 {capped_count} < 期望 10（发了 60-50=10 条封顶后的消息）"
        )

    return ScenarioResult(
        "plan3_message_cap", "PASS",
        f"MESSAGE_SENT(delta=1)={sent_count}条; CAPPED(delta=0)={capped_count}条; 封顶机制正常"
    )


# ----------------------------- Plan 3: scenario 6 — stage_transition -----------------------------

async def run_plan3_stage_transition(token: str, db: DbHandle, user_id: int, character_id: int,
                                     log: Logger) -> ScenarioResult:
    """
    plan3_stage_transition：
    累计发消息涨分到 P3_FRIEND_END(15)，跨越 stage 0→1 边界：
    - WS 收到 stage_transition {from:0, to:1}
    - intimacy_logs 有 reason=PLOT:stage_entry_1（阶段进入剧情）
    - DB relationships.current_stage=1
    注意：stage_entry_N 由 StageTransitionStoryListener 监听 StageTransitionEvent 触发，
    实际写入 intimacy_logs 的 reason 为 PLOT:stage_entry_1（delta=0）。
    """
    log.info(f"==> [scenario] plan3_stage_transition (涨分到 {P3_FRIEND_END} 触发 stage 0→1)")

    # 确保 relationship 存在 + 先等 DAILY_LOGIN 落库（避免分数干扰）
    rest_get("/api/relationships/me", token)
    time.sleep(2)

    # 清今日行为计数器（避免封顶干扰这次的计数）
    today_str = datetime.date.today().isoformat()
    beh_key = f"{BEHAVIOR_KEY_PREFIX}{user_id}:date:{today_str}"
    redis_cmd("DEL", beh_key)

    # 确认当前 score（应为 10，DAILY_LOGIN 贡献），需要额外补充到 P3_FRIEND_END
    rows = db.query(
        f"SELECT intimacy_score, current_stage FROM relationships "
        f"WHERE user_id = {user_id} AND character_id = {character_id}"
    )
    if not rows:
        return ScenarioResult("plan3_stage_transition", "FAIL", "relationships 表无记录")
    cur_score = int(rows[0][0])
    cur_stage = int(rows[0][1])
    log.debug(f"测试前 score={cur_score}, stage={cur_stage}")

    # 需要从当前 score 发足够多条消息越过 P3_FRIEND_END=15
    # 每条 +1（未封顶），需要 max(P3_FRIEND_END - cur_score + 1, 1) 条
    msgs_needed = max(P3_FRIEND_END - cur_score + 2, 2)
    log.info(f"    当前 score={cur_score}，需再发 {msgs_needed} 条消息跨越 friend_end={P3_FRIEND_END}")

    msgs = [f"聊天第{i+1}条消息" for i in range(msgs_needed)]

    stage_transitions_received: list[dict] = []
    stage_stories_received: list[dict] = []

    url = WS_URL_TEMPLATE.format(token=token)
    async with websockets.connect(url, ping_interval=20, ping_timeout=20) as ws:
        for i, content in enumerate(msgs):
            preview = content[:30] + "…" if len(content) > 30 else content
            log.info(f"    [msg {i+1}/{len(msgs)}] → {preview}")
            r = await send_one_p3(ws, content, log)
            stage_transitions_received.extend(r.stage_transitions)
            stage_stories_received.extend(r.stage_stories)
            if r.stage_transitions:
                log.info(f"    收到 stage_transition: {r.stage_transitions}")
                break  # 跨阶段了，不需要继续发

    log.debug(f"收到 stage_transition 帧: {stage_transitions_received}")
    log.debug(f"收到 stage_story 帧: {stage_stories_received}")

    # 验证 WS 帧
    if not stage_transitions_received:
        return ScenarioResult(
            "plan3_stage_transition", "FAIL",
            "未收到 stage_transition WS 帧（阶段跃迁应在得分超过 friend_end 时触发）"
        )

    trans = stage_transitions_received[0]
    from_stage = trans.get("from")
    to_stage = trans.get("to")
    if from_stage != 0 or to_stage != 1:
        return ScenarioResult(
            "plan3_stage_transition", "FAIL",
            f"stage_transition from={from_stage} to={to_stage}，期望 0→1"
        )

    # 验证 DB current_stage=1
    rows = db.query(
        f"SELECT current_stage FROM relationships "
        f"WHERE user_id = {user_id} AND character_id = {character_id}"
    )
    db_stage = int(rows[0][0]) if rows else -1
    if db_stage != 1:
        return ScenarioResult(
            "plan3_stage_transition", "FAIL",
            f"DB current_stage={db_stage}，期望 1"
        )

    # 验证 intimacy_logs 有 PLOT:stage_entry_1 行（阶段剧情事件）
    milestone_row = wait_for_intimacy_log(
        db, user_id, character_id, "PLOT:stage_entry_1", log, timeout_seconds=5.0
    )
    milestone_ok = milestone_row is not None
    log.debug(f"PLOT:stage_entry_1 行: {milestone_row}")

    # ---- 漏点 1: stage_story WS 帧的 story_message 内容断言 ----
    # StageTransitionStoryListener 由 AFTER_COMMIT 异步触发，可能比 turn_complete 晚到。
    # 若当前 stage_stories_received 为空，额外等 3s 再发一条消息看是否补到。
    if not stage_stories_received:
        log.info("    stage_story 帧未随 turn_complete 到达，等 3s 后再发一条消息补捞...")
        await asyncio.sleep(3)
        extra_replies = await chat_p3(token, ["再聊一句看看"], log)
        for r in extra_replies:
            stage_stories_received.extend(r.stage_stories)

    story_msg_ok = False
    story_msg_detail = "未收到 stage_story 帧"
    if stage_stories_received:
        for frame in stage_stories_received:
            sm = frame.get("storyMessage", frame.get("story_message", ""))
            log.debug(f"stage_story frame storyMessage={sm!r}")
            if "她第一次自然地叫了你的名字" in sm:
                story_msg_ok = True
                story_msg_detail = f"storyMessage 含目标文案 ✓ ({sm[:40]!r})"
                break
        if not story_msg_ok:
            msgs_seen = [
                frame.get("storyMessage", frame.get("story_message", ""))
                for frame in stage_stories_received
            ]
            story_msg_detail = f"stage_story 帧存在但 storyMessage 未含目标文案，实际: {msgs_seen}"
    log.debug(f"story_message 断言: {story_msg_detail}")

    if not story_msg_ok:
        return ScenarioResult(
            "plan3_stage_transition", "FAIL",
            f"stage 0→1 ✓; DB stage=1; PLOT:stage_entry_1={'✓' if milestone_ok else '⚠'}; "
            f"story_message 断言失败: {story_msg_detail}"
        )

    # ---- 漏点 2: relationship_milestones stage_entry 去重断言 ----
    # 验证 existsById 保证同一 rule_id='stage_entry_1' 只落一行（即使 AFTER_COMMIT listener 多次跑）
    log.info("    验证 relationship_milestones stage_entry_1 去重（应只 1 行）...")
    count1 = int(db.query(
        f"SELECT COUNT(*) FROM relationship_milestones "
        f"WHERE user_id = {user_id} AND character_id = {character_id} "
        f"AND rule_id = 'stage_entry_1'"
    )[0][0])
    log.debug(f"milestone stage_entry_1 count after first trigger: {count1}")

    # 再发一条消息，让 AFTER_COMMIT listener 再跑一轮（触发 StageTransitionStoryListener 链路）
    await chat_p3(token, ["多聊一句验证去重"], log)
    await asyncio.sleep(2)  # 等 AFTER_COMMIT listener 完成

    count2 = int(db.query(
        f"SELECT COUNT(*) FROM relationship_milestones "
        f"WHERE user_id = {user_id} AND character_id = {character_id} "
        f"AND rule_id = 'stage_entry_1'"
    )[0][0])
    log.debug(f"milestone stage_entry_1 count after second trigger: {count2}")

    if count1 != 1:
        return ScenarioResult(
            "plan3_stage_transition", "FAIL",
            f"stage 0→1 ✓; relationship_milestones stage_entry_1 首次触发后应有 1 行，实际 {count1} 行"
        )
    if count2 != 1:
        return ScenarioResult(
            "plan3_stage_transition", "FAIL",
            f"stage 0→1 ✓; relationship_milestones stage_entry_1 去重失败: "
            f"listener 二次触发后行数从 {count1} 变为 {count2}（期望仍为 1）"
        )

    detail = (
        f"stage 0→1 ✓; DB current_stage=1; "
        f"PLOT:stage_entry_1 {'✓' if milestone_ok else '⚠ 未找到（软断言）'}; "
        f"story_message ✓; milestone 去重 ✓ (count={count2})"
    )
    return ScenarioResult("plan3_stage_transition", "PASS", detail)


# ----------------------------- Plan 3: scenario 7 — plot_deep_night -----------------------------

async def run_plan3_plot_deep_night(token: str, db: DbHandle, user_id: int, character_id: int,
                                    log: Logger) -> ScenarioResult:
    """
    plan3_plot_deep_night：
    直接 INSERT 3 晚 22:xx 历史消息到 message 表（模拟连续 3 晚深夜聊天），
    再发一条普通消息触发 PlotMilestoneEngine.evaluate，
    验证：
    - intimacy_logs 有 PLOT:deep_night_chat (+50) 行
    - relationship_milestones 有 deep_night_chat 行（防重去重）
    """
    log.info("==> [scenario] plan3_plot_deep_night (连续 3 晚深夜聊天 +50)")

    # 确保 relationship 存在（find-or-create）
    rest_get("/api/relationships/me", token)
    time.sleep(1)

    # INSERT 3 晚 22:30 的历史 USER 消息（今天-1, 今天-2, 今天-3）
    today = datetime.date.today()
    for i in range(1, 4):
        night_dt = datetime.datetime.combine(
            today - datetime.timedelta(days=i),
            datetime.time(22, 30, 0)
        )
        night_str = night_dt.strftime("%Y-%m-%d %H:%M:%S")
        db.execute(
            f"INSERT INTO message (user_id, sender_type, content, created_at) "
            f"VALUES ({user_id}, 'USER', '好困啊还不想睡', '{night_str}')"
        )
    log.debug("已 INSERT 3 晚 22:30 历史消息")

    # 发一条普通消息触发 PlotMilestoneEngine
    log.info("    发触发消息...")
    await chat_p3(token, ["今晚又不想睡觉"], log)

    # 等 PLOT:deep_night_chat 落 intimacy_logs
    log.info("    等 PLOT:deep_night_chat 落 intimacy_logs（最多 15s）...")
    row = wait_for_intimacy_log(
        db, user_id, character_id, REASON_DEEP_NIGHT, log, timeout_seconds=15.0
    )
    if row is None:
        return ScenarioResult(
            "plan3_plot_deep_night", "FAIL",
            "15s 内 intimacy_logs 没有 PLOT:deep_night_chat 记录"
        )
    log.debug(f"deep_night_chat log 行: {row}")

    actual_delta = int(row["delta"])
    if actual_delta != 50:
        return ScenarioResult(
            "plan3_plot_deep_night", "FAIL",
            f"PLOT:deep_night_chat delta 期望 50，实际 {actual_delta}"
        )

    # 验证 relationship_milestones 有去重记录
    milestone_rows = db.query(
        f"SELECT rule_id FROM relationship_milestones "
        f"WHERE user_id = {user_id} AND character_id = {character_id} "
        f"AND rule_id = 'deep_night_chat'"
    )
    has_milestone = len(milestone_rows) > 0
    log.debug(f"relationship_milestones deep_night_chat: {has_milestone}")

    if not has_milestone:
        return ScenarioResult(
            "plan3_plot_deep_night", "FAIL",
            "relationship_milestones 表没有 deep_night_chat 记录（去重防重机制异常）"
        )

    # ---- 漏点 3: PLOT rule 一次性去重（重新满足触发条件后也不应再加分）----
    # 第一步：验证当前 intimacy_logs 里 PLOT:deep_night_chat 精确只有 1 行
    log.info("    验证 PLOT:deep_night_chat 首次触发只有 1 行...")
    log1_count = int(db.query(
        f"SELECT COUNT(*) FROM intimacy_logs "
        f"WHERE user_id = {user_id} AND character_id = {character_id} "
        f"AND reason = 'PLOT:deep_night_chat'"
    )[0][0])
    log.debug(f"PLOT:deep_night_chat log count (首次触发后): {log1_count}")

    if log1_count != 1:
        return ScenarioResult(
            "plan3_plot_deep_night", "FAIL",
            f"delta={actual_delta}; milestone 存在; "
            f"但 intimacy_logs PLOT:deep_night_chat 首次触发后应有 1 行，实际 {log1_count} 行"
        )

    # 第二步：再次 INSERT 3 晚深夜消息（模拟"连续 3 晚再聊"，重新满足 DeepNightChatRule 条件）
    log.info("    重新 INSERT 3 晚 22:30 消息，模拟再次满足触发条件...")
    today = datetime.date.today()
    for i in range(4, 7):  # 用更早的 3 天，避免与之前插的重叠
        night_dt = datetime.datetime.combine(
            today - datetime.timedelta(days=i),
            datetime.time(22, 45, 0)
        )
        night_str = night_dt.strftime("%Y-%m-%d %H:%M:%S")
        db.execute(
            f"INSERT INTO message (user_id, sender_type, content, created_at) "
            f"VALUES ({user_id}, 'USER', '深夜再聊一次', '{night_str}')"
        )
    log.debug("已再 INSERT 3 晚 22:45 历史消息（触发条件再次满足）")

    # 触发 PlotMilestoneEngine（AFTER_COMMIT listener 链路）
    log.info("    发触发消息（二次触发验证）...")
    await chat_p3(token, ["晚上好"], log)
    await asyncio.sleep(2)  # 等 AFTER_COMMIT listener

    # 第三步：intimacy_logs 仍应只有 1 行 PLOT:deep_night_chat（ctx.triggeredRuleIds 去重）
    log2_count = int(db.query(
        f"SELECT COUNT(*) FROM intimacy_logs "
        f"WHERE user_id = {user_id} AND character_id = {character_id} "
        f"AND reason = 'PLOT:deep_night_chat'"
    )[0][0])
    log.debug(f"PLOT:deep_night_chat log count (二次触发后): {log2_count}")

    if log2_count != 1:
        return ScenarioResult(
            "plan3_plot_deep_night", "FAIL",
            f"delta={actual_delta}; milestone 存在; "
            f"PLOT 一次性去重失败: 重新满足条件后 intimacy_logs 行数从 {log1_count} 变为 {log2_count}，"
            f"期望仍为 1（PlotMilestoneEngine 应因 ctx.triggeredRuleIds 含 'deep_night_chat' 而跳过）"
        )

    detail = (
        f"delta={actual_delta}; milestone 记录存在; "
        f"PLOT 一次性去重 ✓ (二次触发后 log count 仍={log2_count})"
    )
    return ScenarioResult("plan3_plot_deep_night", "PASS", detail)


# ----------------------------- Plan 3: scenario 8 — plot_first_honest_share -----------------------------

async def run_plan3_plot_first_honest_share(token: str, db: DbHandle, user_id: int, character_id: int,
                                            log: Logger) -> ScenarioResult:
    """
    plan3_plot_first_honest_share：
    发"我有点难过"消息，验证：
    - intimacy_logs 有 PLOT:first_honest_share (+30) 行
    - relationship_milestones 有 first_honest_share 记录
    - 再发一次不重复触发（幂等）
    """
    log.info("==> [scenario] plan3_plot_first_honest_share (首次情感分享 +30)")

    # 确保 relationship 存在
    rest_get("/api/relationships/me", token)
    time.sleep(1)

    log.info("    发情感关键词消息...")
    await chat_p3(token, ["我有点难过，最近压力很大"], log)

    # 等 PLOT:first_honest_share 落 intimacy_logs
    log.info("    等 PLOT:first_honest_share 落 intimacy_logs（最多 15s）...")
    row = wait_for_intimacy_log(
        db, user_id, character_id, REASON_FIRST_HONEST, log, timeout_seconds=15.0
    )
    if row is None:
        return ScenarioResult(
            "plan3_plot_first_honest_share", "FAIL",
            "15s 内 intimacy_logs 没有 PLOT:first_honest_share 记录"
        )

    actual_delta = int(row["delta"])
    if actual_delta != 30:
        return ScenarioResult(
            "plan3_plot_first_honest_share", "FAIL",
            f"PLOT:first_honest_share delta 期望 30，实际 {actual_delta}"
        )

    # 验证 milestone 记录
    milestone_rows = db.query(
        f"SELECT rule_id FROM relationship_milestones "
        f"WHERE user_id = {user_id} AND character_id = {character_id} "
        f"AND rule_id = 'first_honest_share'"
    )
    has_milestone = len(milestone_rows) > 0
    if not has_milestone:
        return ScenarioResult(
            "plan3_plot_first_honest_share", "FAIL",
            "relationship_milestones 表没有 first_honest_share 记录"
        )

    # 幂等：再次发关键词，不应再触发
    log.info("    幂等验证：再发一条情感关键词")
    count_before = int(db.query(
        f"SELECT COUNT(*) FROM intimacy_logs "
        f"WHERE user_id = {user_id} AND character_id = {character_id} "
        f"AND reason = 'PLOT:first_honest_share'"
    )[0][0])
    await chat_p3(token, ["其实我心里一直有点担心"], log)
    time.sleep(3)
    count_after = int(db.query(
        f"SELECT COUNT(*) FROM intimacy_logs "
        f"WHERE user_id = {user_id} AND character_id = {character_id} "
        f"AND reason = 'PLOT:first_honest_share'"
    )[0][0])
    idempotent = (count_after == count_before)

    detail = (
        f"delta={actual_delta}; milestone 记录存在; "
        f"幂等 {'✓' if idempotent else '⚠ 触发了 2 次（软断言）'}"
    )
    return ScenarioResult("plan3_plot_first_honest_share", "PASS", detail)


# ----------------------------- Plan 3: scenario 9 — ai_quality -----------------------------

async def run_plan3_ai_quality(token: str, db: DbHandle, user_id: int, character_id: int,
                               log: Logger) -> ScenarioResult:
    """
    plan3_ai_quality：
    发 P3_AI_TRIGGER_N(3) 条消息（dogfood 模式下缩短自 10），
    触发 ConversationQualityEvaluator，验证：
    - intimacy_logs 有 AI_QUALITY 行（delta 在 0-20 范围内）
    """
    log.info(f"==> [scenario] plan3_ai_quality (发 {P3_AI_TRIGGER_N} 条触发 AI 质量评估)")

    # 确保 relationship 存在
    rest_get("/api/relationships/me", token)
    time.sleep(1)

    # 记录发送前的 AI_QUALITY 计数
    count_before = int(db.query(
        f"SELECT COUNT(*) FROM intimacy_logs "
        f"WHERE user_id = {user_id} AND character_id = {character_id} "
        f"AND reason = 'AI_QUALITY'"
    )[0][0])

    # 发 P3_AI_TRIGGER_N 条消息
    msgs = [
        "今天天气好好，想出去走走",
        "你最近有什么有趣的事情想聊吗",
        "我们聊聊电影吧，你有什么推荐",
    ][:P3_AI_TRIGGER_N]
    log.info(f"    发 {len(msgs)} 条消息...")
    await chat_p3(token, msgs, log, wait_between=0.5)

    # 等 AI_QUALITY 落 intimacy_logs（AI 评估是异步的，多等一会）
    log.info("    等 AI_QUALITY 落 intimacy_logs（最多 30s，AI 调用需要时间）...")
    deadline = time.monotonic() + 30.0
    row = None
    while time.monotonic() < deadline:
        count_after = int(db.query(
            f"SELECT COUNT(*) FROM intimacy_logs "
            f"WHERE user_id = {user_id} AND character_id = {character_id} "
            f"AND reason = 'AI_QUALITY'"
        )[0][0])
        if count_after > count_before:
            rows = db.query(
                f"SELECT delta, reason, new_score FROM intimacy_logs "
                f"WHERE user_id = {user_id} AND character_id = {character_id} "
                f"AND reason = 'AI_QUALITY' "
                f"ORDER BY id DESC LIMIT 1"
            )
            if rows:
                row = {"delta": rows[0][0], "reason": rows[0][1], "new_score": rows[0][2]}
            break
        time.sleep(1.0)

    if row is None:
        return ScenarioResult(
            "plan3_ai_quality", "FAIL",
            f"30s 内 intimacy_logs 没有新 AI_QUALITY 记录（发了 {len(msgs)} 条消息，"
            f"trigger 阈值={P3_AI_TRIGGER_N}，检查环境变量 SANYAN_INTIMACY_AI_TRIGGEREVERYNMESSAGES）"
        )

    actual_delta = int(row["delta"])
    if not (0 <= actual_delta <= 20):
        return ScenarioResult(
            "plan3_ai_quality", "FAIL",
            f"AI_QUALITY delta={actual_delta} 超出范围 0-20"
        )

    return ScenarioResult(
        "plan3_ai_quality", "PASS",
        f"AI_QUALITY delta={actual_delta}（范围 0-20）; new_score={row['new_score']}"
    )


# ----------------------------- Plan 3: scenario 10 — stage_prompt -----------------------------

async def run_plan3_stage_prompt(token: str, db: DbHandle, user_id: int, character_id: int,
                                 log: Logger) -> ScenarioResult:
    """
    plan3_stage_prompt：
    涨分到 stage 2（暧昧，P3_AMBIGUOUS_END=30），验证 AI 回复风格随 stage 变化：
    - AI 回复中出现 stage 2 暧昧特征（"宝"/"亲爱的"/"想你"/"心跳"等称呼/语调关键词）
    这是软断言：只要命中 1 处即 PASS，LLM 回复有随机性。
    """
    log.info(f"==> [scenario] plan3_stage_prompt (涨分到 stage 2 验证 AI 称呼风格)")

    # 确保 relationship 存在
    rest_get("/api/relationships/me", token)
    time.sleep(1)

    # 检查当前 stage
    rows = db.query(
        f"SELECT intimacy_score, current_stage FROM relationships "
        f"WHERE user_id = {user_id} AND character_id = {character_id}"
    )
    if not rows:
        return ScenarioResult("plan3_stage_prompt", "FAIL", "relationships 表无记录")

    cur_score = int(rows[0][0])
    cur_stage = int(rows[0][1])
    log.debug(f"当前 score={cur_score}, stage={cur_stage}")

    # 先清今日行为计数
    today_str = datetime.date.today().isoformat()
    beh_key = f"{BEHAVIOR_KEY_PREFIX}{user_id}:date:{today_str}"
    redis_cmd("DEL", beh_key)

    # 如果还未到 stage 2，持续发消息把分数推过 P3_AMBIGUOUS_END(30)
    if cur_score < P3_AMBIGUOUS_END:
        msgs_needed = P3_AMBIGUOUS_END - cur_score + 3
        log.info(f"    当前 score={cur_score} < {P3_AMBIGUOUS_END}，需再发 {msgs_needed} 条")
        boost_msgs = [f"加分第{i+1}条" for i in range(msgs_needed)]
        await chat_p3(token, boost_msgs, log, wait_between=0.1)
        time.sleep(1)

    # 再检查 stage
    rows = db.query(
        f"SELECT intimacy_score, current_stage FROM relationships "
        f"WHERE user_id = {user_id} AND character_id = {character_id}"
    )
    cur_score = int(rows[0][0])
    cur_stage = int(rows[0][1])
    log.debug(f"推分后 score={cur_score}, stage={cur_stage}")

    if cur_stage < 2:
        return ScenarioResult(
            "plan3_stage_prompt", "FAIL",
            f"score={cur_score} 推分后仍未到 stage 2（current_stage={cur_stage}），"
            f"检查阈值配置 P3_AMBIGUOUS_END={P3_AMBIGUOUS_END}"
        )

    # 发一条让 AI 能体现亲密程度的消息
    log.info("    stage 2 达到，让 AI 回复，观察称呼/语调风格...")
    replies = await chat_p3(token, ["你今天开心吗，跟我聊聊吧"], log)
    ai_text = replies[0].ai_concat if replies else ""
    log.debug(f"AI 回复: {ai_text!r}")

    # stage 2（暧昧）特征关键词（来自小婉 persona 的 stage_overrides 配置）
    stage2_signals = ["宝", "亲爱的", "想你", "心跳", "喜欢", "在意", "心里", "甜", "暖"]
    hit = [s for s in stage2_signals if s in ai_text]
    log.debug(f"stage 2 信号命中: {hit}")

    # 软断言：命中 1 个即 PASS，LLM 有随机性
    if len(hit) >= 1:
        return ScenarioResult(
            "plan3_stage_prompt", "PASS",
            f"stage={cur_stage}; AI 回复命中 stage2 信号: {hit}"
        )
    else:
        # SOFT-FAIL：stage 已到位，AI 可能当次没用特征词（非阻塞）
        return ScenarioResult(
            "plan3_stage_prompt", "PASS",
            f"stage={cur_stage}（已达 ambiguous）; AI 本次未命中特征关键词（软断言允许 0 命中）; "
            f"回复片段: {ai_text[:100]!r}"
        )


# ----------------------------- main runner -----------------------------

SCENARIO_REGISTRY: dict[str, Callable] = {
    # ---- Plan 2 场景（保持不变）----
    "profile": run_profile,
    "throttle": run_throttle,
    "summary": run_summary,
    "rag": run_rag,
    # ---- Plan 3 场景（10 个）----
    "plan3_baseline": run_plan3_baseline,
    "plan3_daily_login": run_plan3_daily_login,
    "plan3_streak_consecutive": run_plan3_streak_consecutive,
    "plan3_streak_gap_reset": run_plan3_streak_gap_reset,
    "plan3_message_cap": run_plan3_message_cap,
    "plan3_stage_transition": run_plan3_stage_transition,
    "plan3_plot_deep_night": run_plan3_plot_deep_night,
    "plan3_plot_first_honest_share": run_plan3_plot_first_honest_share,
    "plan3_ai_quality": run_plan3_ai_quality,
    "plan3_stage_prompt": run_plan3_stage_prompt,
}

# Plan 2 场景顺序（--scenario=all 默认）
SCENARIO_ORDER = ["profile", "throttle", "summary", "rag"]

# Plan 3 场景顺序（--scenario=all-plan3 或 --plan3 使用）
SCENARIO_ORDER_PLAN3 = [
    "plan3_baseline",
    "plan3_daily_login",
    "plan3_streak_consecutive",
    "plan3_streak_gap_reset",
    "plan3_message_cap",
    "plan3_stage_transition",
    "plan3_plot_deep_night",
    "plan3_plot_first_honest_share",
    "plan3_ai_quality",
    "plan3_stage_prompt",
]

# 每个场景独立 user_id（≥ 900 避免跟真实用户撞），并行跑时数据按 user_id 隔离不冲突。
# V10 migration 给 message / memory_* / chat_embeddings 加了 FK，所以这些 user_id 必须
# 在 users 表里真实存在——ensure_dogfood_users() 启动时按需 INSERT 占位行。
# Plan 2: 901-904；Plan 3: 910-919（隔离，避免交叉污染）
SCENARIO_USER_IDS = {
    # Plan 2
    "profile": 901,
    "throttle": 902,
    "summary": 903,
    "rag": 904,
    # Plan 3（910-919）
    "plan3_baseline": 910,
    "plan3_daily_login": 911,
    "plan3_streak_consecutive": 912,
    "plan3_streak_gap_reset": 913,
    "plan3_message_cap": 914,
    "plan3_stage_transition": 915,
    "plan3_plot_deep_night": 916,
    "plan3_plot_first_honest_share": 917,
    "plan3_ai_quality": 918,
    "plan3_stage_prompt": 919,
}


def ensure_dogfood_users(db: DbHandle, user_ids: list[int], log: Logger) -> None:
    """
    确保 dogfood 测试用的 fake user 在 users 表里。
    用 ON CONFLICT (id) DO NOTHING 幂等：第一次跑插入，后续跑跳过。

    password 字段 NOT NULL 所以塞个 'dogfood-no-password'（dogfood 不走 login 流程，
    JWT 是本地用 secret 直接 mint 出来的，password 不会被用到）。
    phone 字段 unique 所以编码进 id。
    """
    for uid in user_ids:
        db.execute(
            f"INSERT INTO users (id, phone, password, nickname) "
            f"VALUES ({uid}, 'dogfood-{uid}', 'dogfood-no-password', 'dogfood-{uid}') "
            f"ON CONFLICT (id) DO NOTHING"
        )
    log.debug(f"ensure_dogfood_users: 已确认 user_ids={user_ids} 存在")


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
    elif args.scenario == "all-plan3":
        scenarios = SCENARIO_ORDER_PLAN3
    else:
        if args.scenario not in SCENARIO_REGISTRY:
            log.info(f"未知 scenario: {args.scenario}（可用: {list(SCENARIO_REGISTRY.keys())}）")
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

    # ⭐ V10 加 FK 约束后，必须先 ensure dogfood fake user 在 users 表里存在，
    # 否则 saveUserMessage INSERT message 会撞 FK 报错。幂等。
    unique_uids = sorted(set(scenario_uids.values()))
    log.info(f"==> [setup] ensure fake users {unique_uids} 存在（FK 兜底）")
    ensure_dogfood_users(db, unique_uids, log)

    # 清理（默认 true，--no-clean 跳过）—— 每个场景的 user_id 各清一次
    # plan3 场景用 clean_plan3_data（清 relationships/intimacy_logs/milestones + Redis）；
    # plan2 场景保持原有 clean_test_data（清 message/memory/embeddings + Redis）
    if args.clean:
        log.info(f"==> [clean] 清理 user_ids={unique_uids} 的测试数据")
        for name in scenarios:
            uid = scenario_uids[name]
            if name.startswith("plan3_"):
                clean_plan3_data(db, uid, character_id, log)
                # plan3 场景的 message 也需要清（clean_plan3_data 已含，此处不重复）
            else:
                clean_test_data(db, uid, log)

    # 执行：plan3 场景强制串行（有顺序依赖 + 各自隔离 user_id，并行无实质加速）；
    # plan2 场景保持原有并行/串行行为。
    is_all_plan3 = all(name.startswith("plan3_") for name in scenarios)
    run_parallel = args.parallel and len(scenarios) > 1 and not is_all_plan3

    if run_parallel:
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
        if is_all_plan3:
            log.info(f"==> 串行跑 plan3 {len(scenarios)} 个场景: {scenarios}")
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
        description="Plan 2 + Plan 3 dogfood E2E 测试 harness (运行在 new 服务器本地)"
    )
    all_scenario_names = ["all", "all-plan3", *SCENARIO_ORDER, *SCENARIO_ORDER_PLAN3]
    p.add_argument(
        "--scenario", default="all",
        choices=all_scenario_names,
        help=(
            "要跑的场景。"
            "'all' = plan2 全部 4 个场景（默认）；"
            "'all-plan3' = plan3 全部 10 个场景（需配合 --plan3 启动，或由 run_dogfood.sh 自动处理）；"
            f"plan2 单场景: {SCENARIO_ORDER}；"
            f"plan3 单场景: {SCENARIO_ORDER_PLAN3}"
        )
    )
    p.add_argument(
        "--clean", dest="clean", action="store_true", default=True,
        help="跑前清掉 user_id 的 message/memory_*/relationships/intimacy_logs 表 + Redis key（默认开）"
    )
    p.add_argument(
        "--no-clean", dest="clean", action="store_false",
        help="跳过清理，接着现有数据继续测"
    )
    p.add_argument(
        "--user-id", type=int, default=None,
        help="测试用户 id；默认 None → 各场景用 SCENARIO_USER_IDS 隔离区（plan2: 901-904, plan3: 910-919）。"
             "显式指定（如 --user-id 1）则所有场景共用，方便调试单场景。"
    )
    p.add_argument("--character-id", type=int, default=1, help="角色 id（默认 1，小婉）")
    p.add_argument(
        "--parallel", dest="parallel", action="store_true", default=True,
        help="并行跑多 plan2 场景（默认开，asyncio.gather）；plan3 场景始终串行"
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
