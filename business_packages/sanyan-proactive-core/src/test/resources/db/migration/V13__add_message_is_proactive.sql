-- ----------- message.is_proactive -----------
-- 标记 AI 主动推送消息（早安/晚安/关怀等无用户触发）；false=用户消息后的对话回复。
-- NOT NULL + 默认 false：存量历史行自动为 false，不回填（历史无法可靠区分，符合排查需求）。
ALTER TABLE message ADD COLUMN is_proactive boolean NOT NULL DEFAULT false;
