-- ============================================================
-- V9: memory_summaries 加唯一约束 (user_id, character_id, period_end_message_id)
--
-- 背景 (Plan 2.6 patch):
--   N3 SummaryScheduler 用 @Async + @TransactionalEventListener(AFTER_COMMIT)
--   异步评估摘要触发。在「user 消息 + AI 回复」几乎同时落库时，两条
--   MessagePersistedEvent 会派两个 @Async 线程并发跑评估，
--   都看到 latest summary 为空就重复跑 LLM + 重复 INSERT，导致
--   同 period_end_message_id 落库 2 条 + 浪费一次 LLM 调用。
--
--   三层防御：
--     L1) Redis SETNX 锁 (SummaryScheduler 内 30s TTL)
--     L2) 本 migration 的 DB UNIQUE 约束（兜底跨实例 / Redis 故障）
--     L3) Repository.save 抛 DataIntegrityViolationException 时 catch + log.warn
--
-- 注意：
--   - period_end_message_id 在 V5 里允许 NULL（首次摘要前未确定终点）。
--     PG 15+ 起支持 NULLS NOT DISTINCT，本项目用 PG 17，能让 NULL 之间
--     也参与去重（两条 period_end_message_id 都为 NULL 的行也视为重复）。
--   - 加约束前先 DELETE USING 去掉历史重复行：dogfood 期已经在线上产生过
--     一次重复（同一组 user/char/period_end 落了 2 条）。
--     保留每组的最小 id，删掉其他 id 更大的。
-- ============================================================

-- 1) 先清理历史重复行：保留每组 (user_id, character_id, period_end_message_id) 的最小 id
DELETE FROM memory_summaries a USING memory_summaries b
WHERE a.id > b.id
  AND a.user_id = b.user_id
  AND a.character_id = b.character_id
  AND a.period_end_message_id IS NOT DISTINCT FROM b.period_end_message_id;

-- 2) 加唯一约束。PG 17 支持 NULLS NOT DISTINCT，让 NULL 也参与去重
ALTER TABLE memory_summaries
ADD CONSTRAINT uq_memory_summaries_period_end
UNIQUE NULLS NOT DISTINCT (user_id, character_id, period_end_message_id);
