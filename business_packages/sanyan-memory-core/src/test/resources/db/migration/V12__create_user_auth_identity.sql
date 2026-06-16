-- 第三方登录身份表：一个 user 可挂多条身份（APPLE/WECHAT）
CREATE TABLE user_auth_identity (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES users(id),
    provider    VARCHAR(16)  NOT NULL CHECK (provider IN ('APPLE', 'WECHAT')),
    external_id VARCHAR(128) NOT NULL CHECK (length(trim(external_id)) > 0),
    raw_openid  VARCHAR(128),
    created_at  TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uk_provider_external UNIQUE (provider, external_id),
    CONSTRAINT uk_provider_user     UNIQUE (provider, user_id)
);
CREATE INDEX idx_uai_user ON user_auth_identity(user_id);

-- 第三方账号没有手机号 / 密码，user 表 phone / password 改可空
ALTER TABLE users ALTER COLUMN phone DROP NOT NULL;
ALTER TABLE users ALTER COLUMN password DROP NOT NULL;
