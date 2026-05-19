-- Plan 3 MVP：{nickname} 动态替换留 Plan 5+ 实现。
-- 此处把 stage 1 的 address 改为通用称呼"你"，与 stage 0 一致，避免 prompt 中出现未替换的占位符。
UPDATE ai_character
SET persona_config = jsonb_set(
    persona_config,
    '{stage_overrides,1,address}',
    '"你"'::jsonb
)
WHERE name = '小婉';
