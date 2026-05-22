# 记忆召回 Dogfood 测试手册

## 概览

记忆召回测试是 Plan 2 长期记忆系统的**端到端增强**：Plan 2 原有的 4 个场景
（`profile / throttle / summary / rag`）只验证「记忆是否落库」，记忆召回场景进一步
验证「落库的记忆是否真的被 AI 在后续对话中召回」。

共 3 个场景，分别对应长期记忆的 3 条通道：

| 场景 | 通道 | 验证点 |
|---|---|---|
| `memory_recall_profile` | profile 画像 | 暴露的身份事实进入画像，被后续对话召回 |
| `memory_recall_summary` | summary 纪要 | 早期事件被后台纪要保留，被后续对话召回 |
| `memory_recall_rag` | RAG 语义检索 | 早期消息向量入库，被语义相关的提问召回 |

---

## 快速开始

3 个场景已注册进 Plan 2 的 `SCENARIO_ORDER`，跟原有 4 个场景一起跑：

```bash
cd server/scripts/dogfood
./run_dogfood.sh                              # plan2 全部 7 个场景（含 3 个召回）
```

跑单个召回场景（调试用）：

```bash
./run_dogfood.sh --scenario=memory_recall_profile -v
./run_dogfood.sh --scenario=memory_recall_summary -v
./run_dogfood.sh --scenario=memory_recall_rag -v
```

每个场景用独立 `user_id`（905-907），与 Plan 2 原有场景（901-904）、Plan 3
（910-919）完全隔离，可并行跑不冲突。

---

## 测试骨架：6 步流程

3 个场景共用 `run_memory_recall` 骨架，差异仅在植入消息 / 提问 / 期望关键词。

```
clean → plant → wait1 → distract → wait2 → probe
```

| 步骤 | 说明 |
|---|---|
| 1. clean | 清 `user_id` 的 message / memory_profiles / memory_summaries / chat_embeddings + Redis 节流 key |
| 2. plant | 发若干条「事实植入」消息，把要被召回的信息说给 AI |
| 3. wait1 | 轮询对应记忆表出现行（30s 上限）。落库超时 → 直接 FAIL，并标注「记忆上游断了，不是召回问题」 |
| 4. distract | 发 35 条无关闲聊消息（`RECALL_DISTRACT_MESSAGES`），把 plant 挤出短期窗口 |
| 5. wait2 | distract 后再次等记忆落库（仅 summary 场景用） |
| 6. probe | 发提问消息，检查 AI 回复是否命中期望关键词（任一命中即 PASS） |

### 为什么要发 35 条 distract 消息

服务端 `MemoryConstants.SHORT_TERM_WINDOW_SIZE = 32`：调 AI 时只把最近 32 条消息
拼进 prompt 的短期上下文。distract 35 条 > 32，确保 plant 阶段的消息被彻底挤出
短期窗口——这样 probe 阶段 AI 若还能答对，**只可能**靠长期记忆（profile / summary /
RAG）召回，而非短期上下文「刚说过还记得」。

`RECALL_DISTRACT_MESSAGES` 是固定 35 条纯闲聊，**绝不含任何场景关键词**，否则 AI 在
distract 阶段顺嘴提到会造成召回测试假阳性。单测
`test_distract_pool_不含场景关键词避免污染` 守护这条不变量。

### wait1 / wait2 的归因价值

记忆召回链路 = 上游（记忆落库）+ 下游（AI 召回）。wait 步骤把两段拆开归因：
落库超时直接 FAIL 并明说「上游断了」，避免把上游故障误判成召回 bug。

---

## 3 个场景详解

### 场景 1：memory_recall_profile（user_id=905）

**目的**：验证暴露身份事实 → profile 画像抽取 → 后续对话召回。

**流程**：
1. plant 5 条暴露身份的消息（姓名、年龄、城市、老家、宠物等）
2. wait1：等 `memory_profiles` 出现行
3. distract 35 条无关消息
4. probe：「对了 你还记得我老家是哪里的吗？」
5. 断言 AI 回复命中 `绵阳` / `四川` / `川` 任一关键词

**验收标准**：AI 回复含老家地名。

---

### 场景 2：memory_recall_summary（user_id=906）

**目的**：验证早期事件 → 后台纪要保留 → 后续对话召回。

**流程**：
1. plant 3 条「吃坏肚子」事件消息（不等 wait1——plant 当下不会立即生成纪要）
2. distract 35 条无关消息，累计消息数越过
   `SUMMARY_TRIGGER_THRESHOLD = 30` 触发后台纪要
3. wait2：等 `memory_summaries` 出现行
4. probe：「前阵子我说过哪家店让我吃坏肚子来着？」
5. 断言 AI 回复命中 `寿司` / `刺身` / `三文鱼` 任一关键词

**验收标准**：AI 回复含早期事件细节。

---

### 场景 3：memory_recall_rag（user_id=907）

**目的**：验证早期消息向量入库 → RAG 语义检索召回。

**流程**：
1. plant 5 条「出差」事件消息（时间、地点、行程等）
2. wait1：等 `chat_embeddings` 出现行（消息切片向量化入库）
3. distract 35 条无关消息
4. probe：「提醒一下，我下周哪天要出差？」——提问措辞不复述 plant 原话，
   靠语义相关而非字面匹配触发 RAG 检索
5. 断言 AI 回复命中 `周三` / `星期三` / `下周三` 任一关键词

**验收标准**：AI 回复含正确出差日期。

---

## 文件清单

| 文件 | 说明 |
|---|---|
| `server/scripts/dogfood/dogfood_test.py` | `run_memory_recall` 骨架 + 3 个场景 + `RECALL_DISTRACT_MESSAGES` |
| `server/scripts/dogfood/test_run_memory_recall.py` | 骨架 / wait helper / distract 池的单元测试 |
| `server/scripts/dogfood/run_dogfood.sh` | 本地 wrapper |
| `server/docs/dogfood/memory-recall-dogfood.md` | 本文档 |

运行单测：

```bash
cd server/scripts/dogfood && pytest test_run_memory_recall.py -v
```

---

## 常见问题

**Q：召回场景 FAIL，怎么区分是上游落库问题还是 AI 召回问题？**
A：看 FAIL detail。wait1/wait2 超时的 FAIL 会明说「上游机制未在 30s 内落库……记忆
上游断了，不是召回问题」，并附 `memory_profiles / memory_summaries / chat_embeddings`
的实际行数。probe 阶段的 FAIL 才是真正的召回失败，detail 含 AI 回复全文。

**Q：AI 有随机性，召回场景会偶发 FAIL 吗？**
A：会有一定概率。期望关键词都给了多个同义词（任一命中即 PASS）来降低误报。若偶发
FAIL，先看 detail 里的 AI 回复全文判断是真没召回还是只是换了说法。

**Q：distract 池能改吗？**
A：能，但改完必须跑 `test_distract_pool_不含场景关键词避免污染` 和
`test_distract_pool_长度恰好35`——填充消息一旦巧合提到场景关键词，会污染召回判定。
