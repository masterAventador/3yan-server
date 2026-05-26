-- V11__create_device_tokens.sql
-- push 域：客户端上报的设备推送 token（本期只搭结构，实推待证书 / SDK）
CREATE TABLE device_tokens (
    id            BIGSERIAL    PRIMARY KEY,
    user_id       BIGINT       NOT NULL,
    platform      VARCHAR(20)  NOT NULL CHECK (platform IN ('ios','android')),
    vendor        VARCHAR(20)  NOT NULL,
    token         VARCHAR(500) NOT NULL,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    registered_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_seen     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, platform, vendor, token)
);
