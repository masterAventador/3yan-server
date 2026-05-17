-- ============================================================
-- V1: 初始 schema（从生产 PG 17 schema-only dump 提取）
-- 含表：ai_character / message / users
-- ============================================================

-- ----------- ai_character -----------
CREATE TABLE ai_character (
    id bigint NOT NULL,
    avatar character varying(255),
    created_at timestamp(6) without time zone,
    name character varying(50) NOT NULL
);

CREATE SEQUENCE ai_character_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE ai_character_id_seq OWNED BY ai_character.id;

ALTER TABLE ai_character ALTER COLUMN id SET DEFAULT nextval('ai_character_id_seq');

ALTER TABLE ai_character ADD CONSTRAINT ai_character_pkey PRIMARY KEY (id);

-- ----------- message -----------
CREATE TABLE message (
    id bigint NOT NULL,
    content text,
    created_at timestamp(6) without time zone,
    sender_type character varying(10) NOT NULL,
    user_id bigint NOT NULL
);

CREATE SEQUENCE message_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE message_id_seq OWNED BY message.id;

ALTER TABLE message ALTER COLUMN id SET DEFAULT nextval('message_id_seq');

ALTER TABLE message ADD CONSTRAINT message_pkey PRIMARY KEY (id);

CREATE INDEX idx_message_created_at ON message (created_at);

CREATE INDEX idx_message_user ON message (user_id, id);

-- ----------- users -----------
CREATE TABLE users (
    id bigint NOT NULL,
    avatar character varying(255),
    created_at timestamp(6) without time zone,
    nickname character varying(50),
    password character varying(255) NOT NULL,
    phone character varying(20) NOT NULL,
    updated_at timestamp(6) without time zone
);

CREATE SEQUENCE users_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE users_id_seq OWNED BY users.id;

ALTER TABLE users ALTER COLUMN id SET DEFAULT nextval('users_id_seq');

ALTER TABLE users ADD CONSTRAINT users_pkey PRIMARY KEY (id);

ALTER TABLE users ADD CONSTRAINT idx_user_phone UNIQUE (phone);
