-- ============================================================
-- V6: memory_profiles 结构化档案表
--
-- 长期记忆「结构化档案」通道：以 (user_id, character_id) 为复合主键，
-- 存储用户在该角色视角下被「记住」的结构化字段（basic_info / preferences /
-- events / emotion_line），合并写时走「读 → JSON merge → version + 1
-- 乐观锁更新」流程，避免并发覆盖。
--
-- 初始 profile_jsonb 默认 '{}' 仅给一个空对象；应用层 Entity（O1 task）在
-- 首次插入时负责扩充到完整结构：
--   {
--     "basic_info":   { "name": null, "age": null, "occupation": null, "hometown": null },
--     "preferences":  { "foods": [], "movies": [], "colors": [], "music": [] },
--     "events":       [],
--     "emotion_line": []
--   }
-- ============================================================

CREATE TABLE memory_profiles (
    user_id        BIGINT NOT NULL,
    character_id   BIGINT NOT NULL,
    profile_jsonb  JSONB NOT NULL DEFAULT '{}',
    version        INT NOT NULL DEFAULT 1,
    updated_at     TIMESTAMP NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, character_id)
);
