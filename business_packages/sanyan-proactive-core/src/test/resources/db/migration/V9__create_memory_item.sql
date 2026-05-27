-- V9__create_memory_item.sql
-- memory 域结构化记忆：一条条带时间的、日后值得主动提起的点
CREATE TABLE memory_item (
    id                BIGSERIAL   PRIMARY KEY,
    user_id           BIGINT      NOT NULL,
    character_id      BIGINT      NOT NULL,
    kind              VARCHAR(20) NOT NULL CHECK (kind IN ('PLAN_EVENT','EMOTION','PROMISE')),
    content           TEXT        NOT NULL,
    salient_at        TIMESTAMPTZ NOT NULL,
    status            VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                      CHECK (status IN ('PENDING','DONE','EXPIRED')),
    source_message_id BIGINT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_memory_item_salient
    ON memory_item (salient_at) WHERE status = 'PENDING';
CREATE INDEX idx_memory_item_user
    ON memory_item (user_id, character_id, status);
