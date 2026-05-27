package com.sanyan.memory.event;

import java.time.Instant;

/**
 * 结构化记忆条目"已排期"领域事件（Plan 4，spec §4.4）。
 *
 * <p>{@link com.sanyan.memory.internal.item.MemoryItemExtractService} 抽出需要排期主动消息的
 * 条目（PLAN_EVENT / EMOTION）并落库后发布；proactive-core 订阅后按 {@code kind} 排
 * {@code events_pending}（c 事件追问 / d 情绪关怀），{@code scheduledAt = salientAt}。
 *
 * <p>事件类放发布方（memory）的 -api/event/（java-backend §6），订阅方只需依赖 memory-api。
 *
 * @param memoryItemId 条目 id（proactive 排期后存 payload，发送时回查 + markDone）
 * @param userId       用户 id
 * @param characterId  角色 id
 * @param kind         条目分类大写 name（PLAN_EVENT / EMOTION）
 * @param salientAt    该冒出来的时间（proactive 用作 scheduled_at）
 */
public record MemoryItemScheduledEvent(
        Long memoryItemId,
        Long userId,
        Long characterId,
        String kind,
        Instant salientAt) {
}
