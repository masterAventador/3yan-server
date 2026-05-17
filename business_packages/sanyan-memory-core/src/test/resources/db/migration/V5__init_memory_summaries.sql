-- ============================================================
-- V5: memory_summaries 滚动摘要表
--
-- 长期记忆「滚动摘要」通道：每 N 条消息后台异步生成一段对话纪要，
-- 用 (user_id, character_id) 区分用户与角色，按时间倒序取最近若干段
-- 拼进 LLM 上下文。
-- ============================================================

CREATE TABLE memory_summaries (
    id                        BIGSERIAL PRIMARY KEY,
    user_id                   BIGINT NOT NULL,
    character_id              BIGINT NOT NULL,
    period_start_message_id   BIGINT,
    period_end_message_id     BIGINT,
    summary_text              TEXT NOT NULL,
    message_count             INT NOT NULL,
    created_at                TIMESTAMP NOT NULL DEFAULT NOW()
);

-- 取「某用户 × 某角色」最新若干段摘要：(user_id, character_id, created_at DESC)
CREATE INDEX idx_memory_summaries_user_char_created
    ON memory_summaries (user_id, character_id, created_at DESC);
