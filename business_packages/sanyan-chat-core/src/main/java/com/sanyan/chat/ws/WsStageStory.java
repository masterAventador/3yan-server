package com.sanyan.chat.ws;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * WS 推送帧：阶段进入剧情演出通知。
 *
 * <p>前端协议约定字段名为 {@code story_message}（snake_case），
 * 故通过 {@link JsonProperty} 做 Java 驼峰 ↔ JSON 下划线的映射。
 *
 * @param type         固定 "stage_story"
 * @param stage        目标阶段编号
 * @param storyMessage 阶段进入时的剧情演出文案
 */
public record WsStageStory(
        String type,
        int stage,
        @JsonProperty("story_message") String storyMessage) {}
