-- 预设 AI 角色（仅在表为空时插入）
-- 人设（systemPrompt / greeting）已挪到 resources/prompts/，改 prompt 不再需要动数据库。
INSERT INTO ai_character (name, avatar, created_at)
SELECT '小婉', NULL, NOW()
WHERE NOT EXISTS (SELECT 1 FROM ai_character WHERE name = '小婉');
