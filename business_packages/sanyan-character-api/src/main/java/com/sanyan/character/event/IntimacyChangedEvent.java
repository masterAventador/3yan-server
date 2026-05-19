package com.sanyan.character.event;

/**
 * 亲密度变更事件。
 * <p>
 * character-core 计算完亲密度后发布，其他模块可订阅。
 *
 * @param userId      用户 ID
 * @param characterId AI 角色 ID
 * @param oldScore    变更前亲密度
 * @param newScore    变更后亲密度
 * @param delta       本次变更量（可为负）
 * @param reason      变更原因标识（如 "MESSAGE_SENT"、"DAILY_LOGIN"）
 */
public record IntimacyChangedEvent(
        Long userId,
        Long characterId,
        int oldScore,
        int newScore,
        int delta,
        String reason) {}
