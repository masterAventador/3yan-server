# Plan 3 Dogfood 测试手册

## 概览

Plan 3 dogfood 测试覆盖亲密度系统的全部业务规则，包含 10 个场景。
测试在 `new` 服务器（49.233.213.109）本地运行，通过缩短阈值加速验证流程。

---

## 快速开始

### 跑 Plan 3 全量 10 个场景（推荐）

```bash
cd server/scripts/dogfood
./run_dogfood.sh --plan3
```

等价于：
```bash
./run_dogfood.sh --plan3 --scenario=all-plan3
```

### 跑单个 Plan 3 场景（调试用）

```bash
./run_dogfood.sh --plan3 --scenario=plan3_daily_login -v
```

### Plan 2 场景不受影响（保持原有用法）

```bash
./run_dogfood.sh                       # plan2 全部 4 个场景
./run_dogfood.sh --scenario=profile    # plan2 单场景
```

---

## 阈值缩短逻辑

Plan 3 dogfood 模式下，通过环境变量覆盖 `IntimacyProperties` 将阶段边界大幅缩短，使测试几分钟内完成，而无需真实的累计天数/消息数。

| 参数 | 生产值 | Dogfood 值 | 说明 |
|---|---|---|---|
| strangerEnd | 100 | 5 | 陌生人→朋友的分数边界 |
| friendEnd | 300 | 15 | 朋友→暧昧的分数边界 |
| ambiguousEnd | 600 | 30 | 暧昧→恋人的分数边界 |
| loverEnd | 1000 | 50 | 恋人→老夫老妻的分数边界 |
| messageDailyCap | 50 | 50 | 每日消息涨分封顶（保持不变） |
| triggerEveryNMessages | 10 | 3 | AI 质量评估触发间隔（缩短） |

覆盖配置定义在 `server/scripts/dogfood/plan3_env_override.env`，由 `run_dogfood.sh --plan3` 自动追加到 `/etc/3yan/3yan-server.env`。

---

## 回滚机制

`run_dogfood.sh --plan3` 通过 `trap EXIT` 确保**无论脚本正常结束、异常退出还是 Ctrl-C 中断**，都会执行回滚。

### 完整流程

1. **测试前**：备份 env 文件到 `/etc/3yan/3yan-server.env.bak-<timestamp>`
2. **追加**：Plan 3 覆盖配置写入 env 文件（用标记行包裹，防止重复追加）
3. **重启**：`sudo systemctl restart sanyan-server`，等待服务就绪（最多 30s）
4. **跑测试**
5. **回滚（trap EXIT）**：
   - 恢复 env 文件：`sudo cp .bak-xxx /etc/3yan/3yan-server.env`
   - 重启服务，等待就绪
   - 测试全部通过时删除备份；异常退出时**保留备份**供排查
6. **清理**：删除远端 `/tmp/dogfood_test.py`

### 手动回滚（异常时）

如果自动回滚失败，手动执行：
```bash
ssh new
sudo cp /etc/3yan/3yan-server.env.bak-<timestamp> /etc/3yan/3yan-server.env
sudo systemctl restart sanyan-server
```

---

## 10 个测试场景详解

所有 Plan 3 场景使用独立 user_id（910-919），与 Plan 2（901-904）完全隔离。

### 场景 1：plan3_baseline（user_id=910）

**目的**：验证 `GET /api/relationships/me` 接口字段完整性。

**流程**：
1. 调用 REST `GET /api/relationships/me`
2. 断言响应包含全部 7 个字段：`userId / characterId / intimacyScore / currentStage / currentStageName / nextStageThreshold / percentToNextStage`
3. 断言 `currentStage` 在 0-4 范围内，`percentToNextStage` 在 0.0-1.0 范围内

**验收标准**：字段完整，无异常值。

---

### 场景 2：plan3_daily_login（user_id=911）

**目的**：验证每日首次进入聊天触发 `DAILY_LOGIN` 涨分。

**流程**：
1. `GET /api/relationships/me`（触发 findOrCreate + DAILY_LOGIN）
2. 等待 `intimacy_logs` 出现 `reason=DAILY_LOGIN` 行
3. 断言 `delta=10`（streak=1，bonus=0）
4. 断言 Redis `streak:user:911` hash 中 `streak=1`

**验收标准**：DAILY_LOGIN delta=10，Redis streak=1。

---

### 场景 3：plan3_streak_consecutive（user_id=912）

**目的**：验证连续登录加成（streak bonus）计算正确。

**流程**：
1. 注入 Redis：`streak=3, last_date=昨天`（模拟已连续 3 天）
2. `GET /me` 触发第 4 天登录
3. 断言 `DAILY_LOGIN delta=30`（10 + min(4×5, 50) = 30）
4. 断言 Redis `streak=4`

**验收标准**：delta=30，streak=4。

---

### 场景 4：plan3_streak_gap_reset（user_id=913）

**目的**：验证中断登录后 streak 重置为 1。

**流程**：
1. 注入 Redis：`streak=5, last_date=3天前`（模拟中断）
2. `GET /me` 触发登录
3. 断言 `DAILY_LOGIN delta=10`（streak=1，无 bonus）
4. 断言 Redis `streak=1`

**验收标准**：delta=10，streak 重置为 1。

---

### 场景 5：plan3_message_cap（user_id=914）

**目的**：验证每日消息涨分封顶机制（每日 cap=50）。

**流程**：
1. 清今日行为计数器（从 0 开始）
2. 连续发 60 条消息
3. 统计 `intimacy_logs` 中 `reason=MESSAGE_SENT, delta=1` 行数 → 应为 50
4. 统计 `reason=CAPPED, delta=0` 行数 → 应 ≥ 10

**验收标准**：前 50 条 delta=1，后 10 条 reason=CAPPED。

---

### 场景 6：plan3_stage_transition（user_id=915）

**目的**：验证阶段跃迁（0→1）的 WS 推送和 DB 更新。

**流程**：
1. 持续发消息，将 score 推过 `friendEnd=15`
2. 断言 WS 收到 `stage_transition {from:0, to:1}` 帧
3. 断言 DB `relationships.current_stage=1`
4. 软断言：`intimacy_logs` 有 `PLOT:stage_entry_1` 行（阶段剧情）

**验收标准**：WS 帧 + DB stage 均正确。

---

### 场景 7：plan3_plot_deep_night（user_id=916）

**目的**：验证连续 3 晚深夜聊天规则（+50）。

**流程**：
1. 直接 INSERT 3 晚（昨天-1/2/3 的 22:30）的历史 USER 消息
2. 发一条普通消息触发 PlotMilestoneEngine
3. 等待 `intimacy_logs` 出现 `reason=PLOT:deep_night_chat, delta=50`
4. 断言 `relationship_milestones` 有 `rule_id=deep_night_chat`（去重记录）
5. 再发一条，验证幂等（不再次触发）

**验收标准**：delta=50，milestone 存在，幂等。

---

### 场景 8：plan3_plot_first_honest_share（user_id=917）

**目的**：验证首次情感深度分享规则（+30）。

**流程**：
1. 发消息"我有点难过，最近压力很大"（含关键词"我有点难过"）
2. 等待 `intimacy_logs` 出现 `reason=PLOT:first_honest_share, delta=30`
3. 断言 milestone 记录存在
4. 再次发"其实我心里..."验证幂等

**验收标准**：delta=30，milestone 存在，幂等。

---

### 场景 9：plan3_ai_quality（user_id=918）

**目的**：验证 AI 对话质量评估涨分。

**流程**：
1. 发 3 条消息（dogfood 触发间隔=3）
2. 等待最多 30s（AI 调用异步）
3. 断言 `intimacy_logs` 有 `reason=AI_QUALITY`，`delta` 在 0-20 范围内

**验收标准**：AI_QUALITY 记录存在，delta 合法。

---

### 场景 10：plan3_stage_prompt（user_id=919）

**目的**：验证 stage 2（暧昧）的 AI prompt 风格注入（软断言）。

**流程**：
1. 将 score 推至 `ambiguousEnd=30`（stage 2）
2. 发一条普通消息
3. 检查 AI 回复中是否含有 stage 2 特征词（"宝"/"亲爱的"/"想你"/"心跳"等）

**验收标准**：命中 1 个特征词即 PASS（LLM 有随机性，0 命中也作 PASS 并标注软断言）。

---

## 文件清单

| 文件 | 说明 |
|---|---|
| `server/scripts/dogfood/dogfood_test.py` | Plan 2 + Plan 3 全部场景的 E2E 测试脚本 |
| `server/scripts/dogfood/run_dogfood.sh` | 本地 wrapper，含 `--plan3` 回滚机制 |
| `server/scripts/dogfood/plan3_env_override.conf` | Plan 3 dogfood 阈值覆盖配置（被 run_dogfood.sh 读取追加） |
| `server/docs/dogfood/plan-3-dogfood.md` | 本文档 |

## 常见问题

**Q：测试中途被 Ctrl-C，服务器配置会残留吗？**
A：不会。`run_dogfood.sh` 通过 `trap EXIT` 捕获 SIGINT/SIGTERM，无论何种退出都会执行回滚，并重启服务。备份文件（`.bak-<timestamp>`）在异常退出时保留，方便排查。

**Q：`plan3_streak_consecutive` 和 `plan3_streak_gap_reset` 需要等几天？**
A：不需要。测试通过直接向 Redis 注入 `streak` 和 `last_date` 模拟历史状态，即刻验证，不依赖真实等待天数。

**Q：`plan3_plot_deep_night` 需要熬夜聊天吗？**
A：不需要。测试通过 `INSERT` 历史消息到 `message` 表，伪造 3 晚 22:30 的聊天记录，然后触发规则引擎评估。

**Q：Plan 2 的场景会被影响吗？**
A：不会。Plan 2 和 Plan 3 使用不同的 user_id（901-904 vs 910-919），clean 步骤也分别调用不同清理函数，互不干扰。
