CREATE TABLE intimacy_logs (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT      NOT NULL,
    character_id  BIGINT      NOT NULL,
    delta         INT         NOT NULL,
    reason        VARCHAR(60) NOT NULL,
    metadata      JSONB,
    new_score     INT         NOT NULL,
    new_stage     SMALLINT    NOT NULL,
    created_at    TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_intimacy_logs_user_char_time
    ON intimacy_logs (user_id, character_id, created_at DESC);
