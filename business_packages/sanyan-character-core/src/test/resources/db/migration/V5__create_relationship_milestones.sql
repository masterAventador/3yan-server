CREATE TABLE relationship_milestones (
    user_id       BIGINT      NOT NULL,
    character_id  BIGINT      NOT NULL,
    rule_id       VARCHAR(60) NOT NULL,
    triggered_at  TIMESTAMP   NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, character_id, rule_id)
);
