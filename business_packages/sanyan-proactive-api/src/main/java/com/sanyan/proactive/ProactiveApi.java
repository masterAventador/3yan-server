package com.sanyan.proactive;

import com.sanyan.proactive.dto.ProactiveTriggerResult;

/**
 * 主动消息域对内契约。proactive 主要靠 @Scheduled + 事件订阅驱动，
 * 本接口供测试 / 未来 admin 手动触发用。
 */
public interface ProactiveApi {
    /** 手动触发一次某用户的指定场景调度（测试 / 运维用）。 */
    ProactiveTriggerResult triggerNow(Long userId, Long characterId, String eventType);
}
