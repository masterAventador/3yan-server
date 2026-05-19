package com.sanyan.character.event;

/**
 * 阶段切换剧情演出事件。
 * StageTransitionStoryListener 在 StageTransitionEvent 触发后发布此事件，
 * chat-core 监听此事件并通过 WebSocket 推送 story_message 给前端。
 */
public record StageEntryStoryEvent(
        Long userId,
        Long characterId,
        int toStage,
        String storyMessage) {}
