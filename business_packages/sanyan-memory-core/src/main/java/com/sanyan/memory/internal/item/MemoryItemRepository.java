package com.sanyan.memory.internal.item;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * memory_item 仓储。findByUserIdAndCharacterIdAndStatus 供抽取去重（拉 PENDING 喂 LLM）。
 */
public interface MemoryItemRepository extends JpaRepository<MemoryItemEntity, Long> {
    List<MemoryItemEntity> findByUserIdAndCharacterIdAndStatus(Long userId, Long characterId, MemoryItemStatus status);
}
