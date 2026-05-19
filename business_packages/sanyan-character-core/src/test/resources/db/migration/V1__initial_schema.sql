-- ============================================================
-- V1: 完整 schema baseline（合并历史 V1-V10）
--
-- 项目 dev 阶段，没有线上真实用户数据，所以把 Plan 2 时代分散的 V1/V2/V5/V6/V7/V8/V9/V10
-- 全部合并成一个干净的 baseline，便于后续维护。
--
-- 合并内容：
-- - V1 旧版：ai_character / message / users 三张基础表
-- - V2: seed 小婉角色
-- - V5: memory_summaries 滚动摘要表 + 索引
-- - V6: memory_profiles 结构化档案表
-- - V7: chat_embeddings RAG 向量表 + pgvector 扩展 + ivfflat 索引
-- - V8: memory_profiles 改 free-form summary（drop profile_jsonb + add summary_text）
-- - V9: memory_summaries 唯一约束 (user_id, character_id, period_end_message_id) NULLS NOT DISTINCT
-- - V10: 业务表 user_id / character_id 外键约束（CASCADE / RESTRICT 策略详见 ALTER 行）
-- ============================================================

-- ----------- 扩展 -----------
CREATE EXTENSION IF NOT EXISTS vector;

-- ----------- users -----------
CREATE TABLE users (
    id          BIGSERIAL PRIMARY KEY,
    phone       VARCHAR(20) NOT NULL UNIQUE,
    password    VARCHAR(255) NOT NULL,
    nickname    VARCHAR(50),
    avatar      VARCHAR(255),
    created_at  TIMESTAMP(6),
    updated_at  TIMESTAMP(6)
);

-- ----------- ai_character -----------
CREATE TABLE ai_character (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(50) NOT NULL,
    avatar      VARCHAR(255),
    created_at  TIMESTAMP(6)
);

-- ----------- message -----------
-- user_id ON DELETE CASCADE: 用户注销时关联消息一起删（GDPR 用户权利 + 避免脏数据）
CREATE TABLE message (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    sender_type  VARCHAR(10) NOT NULL,
    content      TEXT,
    created_at   TIMESTAMP(6)
);

CREATE INDEX idx_message_user ON message (user_id, id);
CREATE INDEX idx_message_created_at ON message (created_at);

-- ----------- memory_summaries -----------
-- 长期记忆「滚动摘要」通道：每 N 条消息后台异步生成一段对话纪要。
-- user_id ON DELETE CASCADE；character_id ON DELETE RESTRICT（删 character 但还有用户记忆 = 不允许）
CREATE TABLE memory_summaries (
    id                        BIGSERIAL PRIMARY KEY,
    user_id                   BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    character_id              BIGINT NOT NULL REFERENCES ai_character(id) ON DELETE RESTRICT,
    period_start_message_id   BIGINT,
    period_end_message_id     BIGINT,
    summary_text              TEXT NOT NULL,
    message_count             INT NOT NULL,
    created_at                TIMESTAMP NOT NULL DEFAULT NOW(),
    -- 防 SummaryScheduler @Async 并发双跑导致重复 INSERT
    --（PG 17 NULLS NOT DISTINCT 让 NULL 之间也参与去重）
    CONSTRAINT uq_memory_summaries_period_end
        UNIQUE NULLS NOT DISTINCT (user_id, character_id, period_end_message_id)
);

-- 取「某用户 × 某角色」最新若干段摘要
CREATE INDEX idx_memory_summaries_user_char_created
    ON memory_summaries (user_id, character_id, created_at DESC);

-- ----------- memory_profiles -----------
-- 长期记忆「结构化档案」通道：LLM 维护一段自然语言画像（Plan 2.5 free-form 形态）。
CREATE TABLE memory_profiles (
    user_id        BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    character_id   BIGINT NOT NULL REFERENCES ai_character(id) ON DELETE RESTRICT,
    summary_text   TEXT NOT NULL DEFAULT '',
    version        INT NOT NULL DEFAULT 1,
    updated_at     TIMESTAMP NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, character_id)
);

-- ----------- chat_embeddings -----------
-- 长期记忆「向量检索（RAG）」通道：每 N 条消息切 chunk，BGE-M3 出 1024 维 embedding。
CREATE TABLE chat_embeddings (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    character_id  BIGINT NOT NULL REFERENCES ai_character(id) ON DELETE RESTRICT,
    message_ids   BIGINT[] NOT NULL,
    chunk_text    TEXT NOT NULL,
    embedding     vector(1024) NOT NULL,
    token_count   INT,
    created_at    TIMESTAMP NOT NULL DEFAULT NOW()
);

-- ivfflat ANN 索引：cosine 相似度 + 100 桶（N < 1M 行的默认值，膨胀后调到 sqrt(N)）
CREATE INDEX idx_chat_embeddings_ivfflat
    ON chat_embeddings USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);

-- ============================================================
-- Seed: 默认 AI 角色（小婉）
-- 全局共享，所有用户共用此角色（MVP 单角色阶段；Plan 3 才扩多角色）
-- 用 NOT EXISTS 保持幂等（V1 重跑或 testcontainers 启动多次都不重复插）
-- ============================================================
INSERT INTO ai_character (name, avatar, created_at)
SELECT '小婉', NULL, NOW()
WHERE NOT EXISTS (SELECT 1 FROM ai_character WHERE name = '小婉');
