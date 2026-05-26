package com.sanyan.memory.api;

import com.sanyan.common.error.BusinessException;
import com.sanyan.memory.dto.MemoryItemDto;
import com.sanyan.memory.internal.MemoryErrCode;
import com.sanyan.memory.internal.item.MemoryItemEntity;
import com.sanyan.memory.internal.item.MemoryItemKind;
import com.sanyan.memory.internal.item.MemoryItemRepository;
import com.sanyan.memory.internal.item.MemoryItemStatus;
import com.sanyan.memory.internal.item.fixtures.MemoryItemTestFixtures;
import com.sanyan.memory.internal.orchestrator.MemoryContextBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemoryApiImplTest {

    @Mock MemoryContextBuilder builder;
    @Mock MemoryItemRepository itemRepository;
    @InjectMocks MemoryApiImpl api;

    @Test
    void getMemoryItem_should_map_entity_to_dto() {
        MemoryItemEntity entity = MemoryItemTestFixtures.planEvent(
                7L, 1L, "周三面试", Instant.parse("2026-06-03T09:00:00Z"));
        entity.setId(99L);
        when(itemRepository.findById(99L)).thenReturn(Optional.of(entity));

        MemoryItemDto dto = api.getMemoryItem(99L);

        assertThat(dto.id()).isEqualTo(99L);
        assertThat(dto.userId()).isEqualTo(7L);
        assertThat(dto.characterId()).isEqualTo(1L);
        assertThat(dto.kind()).isEqualTo(MemoryItemKind.PLAN_EVENT.name());
        assertThat(dto.content()).isEqualTo("周三面试");
        assertThat(dto.salientAt()).isEqualTo(Instant.parse("2026-06-03T09:00:00Z"));
        assertThat(dto.status()).isEqualTo(MemoryItemStatus.PENDING.name());
    }

    @Test
    void getMemoryItem_should_throw_when_not_found() {
        when(itemRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> api.getMemoryItem(404L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrCode())
                        .isEqualTo(MemoryErrCode.MEMORY_ITEM_NOT_FOUND));

        verify(itemRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void markMemoryItemDone_should_set_status_done_and_save() {
        MemoryItemEntity entity = MemoryItemTestFixtures.emotion(
                7L, 1L, "最近压力大", Instant.now());
        entity.setId(55L);
        when(itemRepository.findById(55L)).thenReturn(Optional.of(entity));

        api.markMemoryItemDone(55L);

        ArgumentCaptor<MemoryItemEntity> captor = ArgumentCaptor.forClass(MemoryItemEntity.class);
        verify(itemRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(MemoryItemStatus.DONE);
    }

    @Test
    void markMemoryItemDone_should_throw_when_not_found() {
        when(itemRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> api.markMemoryItemDone(404L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrCode())
                        .isEqualTo(MemoryErrCode.MEMORY_ITEM_NOT_FOUND));
    }
}
