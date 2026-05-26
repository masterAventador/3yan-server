package com.sanyan.memory.internal.item;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * memory_item 仓储。findTop20...OrderByIdDesc 供抽取去重（拉最近 20 条 PENDING 喂 LLM）。
 * 限 20 条避免 PENDING 无上限增长撑爆 LLM context window / 抬高费用。
 */
public interface MemoryItemRepository extends JpaRepository<MemoryItemEntity, Long> {
    List<MemoryItemEntity> findTop20ByUserIdAndCharacterIdAndStatusOrderByIdDesc(
            Long userId, Long characterId, MemoryItemStatus status);
}
