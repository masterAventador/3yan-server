package com.sanyan.character.event;

/**
 * 关系阶段跃迁事件。
 * <p>
 * 当用户与角色的关系从一个阶段升级到下一阶段时发布。
 *
 * @param userId      用户 ID
 * @param characterId AI 角色 ID
 * @param fromStage   跃迁前阶段编号
 * @param toStage     跃迁后阶段编号
 */
public record StageTransitionEvent(
        Long userId,
        Long characterId,
        int fromStage,
        int toStage) {}
