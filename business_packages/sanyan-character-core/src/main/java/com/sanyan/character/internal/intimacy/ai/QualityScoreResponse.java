package com.sanyan.character.internal.intimacy.ai;

/**
 * LLM 对话质量评估结果。
 *
 * @param score  质量分（0 - {@code IntimacyProperties.Ai#qualityMaxScore}，默认上限 20）
 * @param reason 评估原因（10 字内，供日志记录）
 */
public record QualityScoreResponse(int score, String reason) {}
