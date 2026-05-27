package com.sanyan.memory.dto;

import java.time.Instant;

/**
 * 结构化记忆条目跨模块 DTO（Plan 4）。proactive 域生成器通过 {@code MemoryApi.getMemoryItem}
 * 拿 {@code content} 拼主动消息文案（"周三面试怎么样啦"）。
 *
 * <p>{@code kind} / {@code status} 为字符串形态（大写 enum name），避免把 memory-core 的
 * 内部枚举 {@code MemoryItemKind} / {@code MemoryItemStatus} 泄漏到 -api 契约。
 */
public record MemoryItemDto(
        Long id,
        Long userId,
        Long characterId,
        String kind,
        String content,
        Instant salientAt,
        String status) {
}
