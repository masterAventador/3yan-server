-- V3__create_relationships.sql
CREATE TABLE relationships (
    user_id         BIGINT   NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    character_id    BIGINT   NOT NULL REFERENCES ai_character(id) ON DELETE RESTRICT,
    intimacy_score  INT      NOT NULL DEFAULT 0,
    current_stage   SMALLINT NOT NULL DEFAULT 0,
    version         INT      NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, character_id)
);
