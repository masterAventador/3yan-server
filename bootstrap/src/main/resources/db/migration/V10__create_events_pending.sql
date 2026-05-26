-- V10__create_events_pending.sql
-- proactive 域调度表：4 触发器写 scheduled，ProactiveScheduler 扫描 due 派发
CREATE TABLE events_pending (
    id            BIGSERIAL   PRIMARY KEY,
    user_id       BIGINT      NOT NULL,
    character_id  BIGINT      NOT NULL,
    event_type    VARCHAR(40) NOT NULL
                  CHECK (event_type IN ('A_GREETING','B_RECALL','C_EVENT_FOLLOWUP','D_EMOTION_CARE')),
    status        VARCHAR(20) NOT NULL DEFAULT 'SCHEDULED'
                  CHECK (status IN ('SCHEDULED','PROCESSING','SENT','FAILED','CANCELLED')),
    payload       JSONB       NOT NULL DEFAULT '{}',
    scheduled_at  TIMESTAMPTZ NOT NULL,
    sent_at       TIMESTAMPTZ,
    fail_count    INT         NOT NULL DEFAULT 0,
    last_error    TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_events_pending_due
    ON events_pending (scheduled_at) WHERE status = 'SCHEDULED';
CREATE INDEX idx_events_pending_dedup
    ON events_pending (user_id, event_type, status);
