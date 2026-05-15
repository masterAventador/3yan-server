-- ============================================================
-- V8: memory_profiles 改为 free-form summary（Plan 2.5 重构）
--
-- 背景: Plan 2 当时设计 profile 用 slot schema（basic_info /
-- preferences / events / emotion_line），但实际下游消费者就是 LLM，
-- 转结构化再转回 prose 是多余的。改成 "LLM 维护一段自然语言画像段落"
-- 更自洽且省一次 LLM 调用。
--
-- 表当前是空表（线上未上线），直接 DROP + ADD 替换列，干净彻底：
--   * 删除 profile_jsonb JSONB 列（slot schema 不再使用）
--   * 新增 summary_text TEXT 列（自然语言画像，由 LLM 直接维护）
--
-- 其余列保持不变：
--   * 复合 PK (user_id, character_id)
--   * version：JPA @Version 乐观锁
--   * updated_at：Hibernate @PreUpdate / @PrePersist 钩子维护
-- ============================================================

ALTER TABLE memory_profiles DROP COLUMN profile_jsonb;
ALTER TABLE memory_profiles ADD COLUMN summary_text TEXT NOT NULL DEFAULT '';
