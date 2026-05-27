package com.sanyan.proactive.internal.fixtures;

import com.sanyan.proactive.internal.EventPendingEntity;
import com.sanyan.proactive.internal.EventStatus;
import com.sanyan.proactive.internal.EventType;

import java.time.Instant;

/** EventPendingEntity Object Mother（java-backend-business-layer.md §5.2）。测试一律经此构造。 */
public final class EventPendingTestFixtures {

    private EventPendingTestFixtures() {}

    public static EventPendingEntity scheduled(Long userId, Long characterId, EventType type, Instant scheduledAt) {
        EventPendingEntity e = new EventPendingEntity();
        e.setUserId(userId);
        e.setCharacterId(characterId);
        e.setEventType(type);
        e.setStatus(EventStatus.SCHEDULED);
        e.setScheduledAt(scheduledAt);
        return e;
    }

    public static EventPendingEntity withPayload(Long userId, Long characterId, EventType type,
                                                 Instant scheduledAt, String jsonPayload) {
        EventPendingEntity e = scheduled(userId, characterId, type, scheduledAt);
        e.setPayload(jsonPayload);
        return e;
    }

    public static EventPendingEntity withStatus(Long userId, Long characterId, EventType type,
                                                Instant scheduledAt, EventStatus status) {
        EventPendingEntity e = scheduled(userId, characterId, type, scheduledAt);
        e.setStatus(status);
        return e;
    }
}
