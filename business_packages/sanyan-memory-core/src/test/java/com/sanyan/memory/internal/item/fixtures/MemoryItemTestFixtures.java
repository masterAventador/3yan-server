package com.sanyan.memory.internal.item.fixtures;

import com.sanyan.memory.internal.item.MemoryItemEntity;
import com.sanyan.memory.internal.item.MemoryItemKind;
import com.sanyan.memory.internal.item.MemoryItemStatus;

import java.time.Instant;

/** MemoryItemEntity Object Mother。测试中需要 MemoryItemEntity 一律通过这里构造。 */
public final class MemoryItemTestFixtures {

    private MemoryItemTestFixtures() {}

    public static MemoryItemEntity planEvent(Long userId, Long characterId, String content, Instant salientAt) {
        return build(userId, characterId, MemoryItemKind.PLAN_EVENT, content, salientAt);
    }

    public static MemoryItemEntity emotion(Long userId, Long characterId, String content, Instant salientAt) {
        return build(userId, characterId, MemoryItemKind.EMOTION, content, salientAt);
    }

    public static MemoryItemEntity promise(Long userId, Long characterId, String content, Instant salientAt) {
        return build(userId, characterId, MemoryItemKind.PROMISE, content, salientAt);
    }

    private static MemoryItemEntity build(Long userId, Long characterId,
                                          MemoryItemKind kind, String content, Instant salientAt) {
        MemoryItemEntity e = new MemoryItemEntity();
        e.setUserId(userId);
        e.setCharacterId(characterId);
        e.setKind(kind);
        e.setContent(content);
        e.setSalientAt(salientAt);
        e.setStatus(MemoryItemStatus.PENDING);
        return e;
    }
}
