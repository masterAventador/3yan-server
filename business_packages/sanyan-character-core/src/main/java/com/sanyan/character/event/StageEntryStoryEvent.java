package com.sanyan.character.event;

/**
 * 阶段进入剧情演出事件。
 *
 * <p>由 {@link StageTransitionStoryListener} 在关系阶段发生跃迁时发布，
 * 携带对应阶段的剧情演出文案，供 WS 推送层消费。
 *
 * @param userId      用户 ID
 * @param characterId 角色 ID
 * @param toStage     目标阶段序号
 * @param story       演出文案
 */
public record StageEntryStoryEvent(
        Long userId,
        Long characterId,
        int toStage,
        String storyMessage) {}
