package com.sanyan.memory.api;

import com.sanyan.common.error.BusinessException;
import com.sanyan.memory.MemoryApi;
import com.sanyan.memory.dto.MemoryContext;
import com.sanyan.memory.dto.MemoryItemDto;
import com.sanyan.memory.internal.MemoryErrCode;
import com.sanyan.memory.internal.item.MemoryItemEntity;
import com.sanyan.memory.internal.item.MemoryItemRepository;
import com.sanyan.memory.internal.item.MemoryItemStatus;
import com.sanyan.memory.internal.orchestrator.MemoryContextBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link MemoryApi} 在 -core 的实现。
 *
 * <p>getRelevantContext 委托 {@link MemoryContextBuilder}（Plan 2）；
 * Plan 4 新增 getMemoryItem / markMemoryItemDone 直接操作 {@link MemoryItemRepository}
 * （简单 CRUD，无复杂编排，按 java-backend §3.3 直接在方法里处理）。
 */
@Service
@RequiredArgsConstructor
public class MemoryApiImpl implements MemoryApi {

    private final MemoryContextBuilder builder;
    private final MemoryItemRepository itemRepository;

    @Override
    public MemoryContext getRelevantContext(Long userId, Long characterId, String currentUserMessage) {
        return builder.build(userId, characterId, currentUserMessage);
    }

    @Override
    public MemoryItemDto getMemoryItem(Long itemId) {
        MemoryItemEntity e = itemRepository.findById(itemId)
                .orElseThrow(() -> new BusinessException(MemoryErrCode.MEMORY_ITEM_NOT_FOUND));
        return toDto(e);
    }

    @Override
    @Transactional
    public void markMemoryItemDone(Long itemId) {
        MemoryItemEntity e = itemRepository.findById(itemId)
                .orElseThrow(() -> new BusinessException(MemoryErrCode.MEMORY_ITEM_NOT_FOUND));
        e.setStatus(MemoryItemStatus.DONE);
        itemRepository.save(e);
    }

    private static MemoryItemDto toDto(MemoryItemEntity e) {
        return new MemoryItemDto(
                e.getId(),
                e.getUserId(),
                e.getCharacterId(),
                e.getKind().name(),
                e.getContent(),
                e.getSalientAt(),
                e.getStatus().name());
    }
}
