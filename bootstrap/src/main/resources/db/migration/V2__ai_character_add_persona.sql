-- Plan 3 Phase A Task A1: ai_character 表加 base_prompt 和 persona_config 列
ALTER TABLE ai_character
    ADD COLUMN base_prompt    TEXT  NOT NULL DEFAULT '',
    ADD COLUMN persona_config JSONB NOT NULL DEFAULT '{}'::jsonb;
