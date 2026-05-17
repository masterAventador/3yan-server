-- ============================================================
-- V7: chat_embeddings 向量检索表 + 启用 pgvector 扩展
--
-- 长期记忆「向量检索（RAG）」通道：把每 N 条消息切片成一段 chunk_text，
-- 用 BGE-M3 生成 1024 维 embedding 写进本表；查询时按当前消息 embedding
-- 做 cosine 相似度 ANN 检索，召回 top-K 历史片段拼进 LLM 上下文。
--
-- 关键设计：
--   - `vector` 类型由 pgvector 扩展提供，本 migration 负责 `CREATE EXTENSION`
--     （生产 PG 已通过 apt 装好 postgresql-17-pgvector，测试容器
--     pgvector/pgvector:pg17 镜像也预装系统库——但两者都需要 SQL 层
--     显式启用，所以放这里）
--   - `embedding vector(1024)` 维度对齐 BGE-M3 输出
--   - `message_ids BIGINT[]` 用 PG 原生数组而非关联表：chunk → message 是
--     固定 N:M 但 N 很小（M2 切片策略每段 ~10 条消息），数组比 join 表
--     更轻量；查询走 chunk 维度，反查 message 罕见
--   - ivfflat 索引使用 cosine 距离（`vector_cosine_ops`），`lists = 100`
--     是 pgvector 推荐的中等数据量默认值（N < 1M 行），后续行数膨胀再
--     调到 `sqrt(N)`
-- ============================================================

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE chat_embeddings (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT NOT NULL,
    character_id  BIGINT NOT NULL,
    message_ids   BIGINT[] NOT NULL,
    chunk_text    TEXT NOT NULL,
    embedding     vector(1024) NOT NULL,
    token_count   INT,
    created_at    TIMESTAMP NOT NULL DEFAULT NOW()
);

-- ivfflat ANN 索引：cosine 相似度 + 100 桶
CREATE INDEX idx_chat_embeddings_ivfflat
    ON chat_embeddings USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
