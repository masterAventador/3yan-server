package com.sanyan.memory.api;

import com.sanyan.common.error.BusinessException;
import com.sanyan.memory.MemoryApi;
import com.sanyan.memory.dto.MemoryContext;
import com.sanyan.memory.dto.MemoryItemDto;
import com.sanyan.memory.internal.MemoryErrCode;
import com.sanyan.memory.internal.item.MemoryItemEntity;
import com.sanyan.memory.internal.item.MemoryItemKind;
import com.sanyan.memory.internal.item.MemoryItemRepository;
import com.sanyan.memory.internal.item.MemoryItemStatus;
import com.sanyan.memory.internal.item.fixtures.MemoryItemTestFixtures;
import com.sanyan.memory.internal.orchestrator.MemoryContextBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link MemoryApiImpl} 单元测试（Plan 2 Q2 + Plan 4 F3），用 Mockito mock 掉
 * {@link MemoryContextBuilder} 与 {@link MemoryItemRepository}，覆盖三组行为：
 *
 * <ul>
 *   <li><b>Plan 2 getRelevantContext 薄壳委托</b>：builder 返回 {@code null} 时透传 {@code null}
 *       （让 chat-core 跳过 system 消息注入）；返回非空 MemoryContext 时原样透传，参数顺序正确。</li>
 *   <li><b>Plan 4 getMemoryItem</b>：命中时把 managed entity 映射为 {@link MemoryItemDto}
 *       （kind/status 转大写 enum name 字符串，不泄漏内部枚举）；未命中抛
 *       {@link MemoryErrCode#MEMORY_ITEM_NOT_FOUND}。</li>
 *   <li><b>Plan 4 markMemoryItemDone</b>：命中时把 status 改为 DONE 依赖 JPA dirty checking
 *       自动 flush（不显式 save）；未命中抛 {@link MemoryErrCode#MEMORY_ITEM_NOT_FOUND}。</li>
 * </ul>
 *
 * <p>三层组合细节由 {@code MemoryContextBuilderTest}（Q1）覆盖，这里只验"薄壳委托/简单 CRUD"语义。
 */
@ExtendWith(MockitoExtension.class)
class MemoryApiImplTest {

    private static final Long USER_ID = 42L;
    private static final Long CHARACTER_ID = 7L;
    private static final String CURRENT_USER_MESSAGE = "今天我去吃了火锅";

    @Mock MemoryContextBuilder builder;
    @Mock MemoryItemRepository itemRepository;
    @InjectMocks MemoryApiImpl api;

    @Test
    @DisplayName("builder 返回 null → ApiImpl 透传 null（让 chat-core 跳过 system 消息注入）")
    void getRelevantContext_returnsNullWhenBuilderReturnsNull() {
        when(builder.build(USER_ID, CHARACTER_ID, CURRENT_USER_MESSAGE)).thenReturn(null);

        MemoryContext result = api.getRelevantContext(USER_ID, CHARACTER_ID, CURRENT_USER_MESSAGE);

        assertThat(result).isNull();
        verify(builder).build(USER_ID, CHARACTER_ID, CURRENT_USER_MESSAGE);
        verifyNoMoreInteractions(builder);
    }

    @Test
    @DisplayName("builder 返回非空 MemoryContext → ApiImpl 原样透传")
    void getRelevantContext_passesThroughNonNullContext() {
        MemoryContext expected = new MemoryContext("【最近的对话纪要】\n聊了猫和工作");
        when(builder.build(USER_ID, CHARACTER_ID, CURRENT_USER_MESSAGE)).thenReturn(expected);

        MemoryContext result = api.getRelevantContext(USER_ID, CHARACTER_ID, CURRENT_USER_MESSAGE);

        assertThat(result).isSameAs(expected);
        verify(builder).build(USER_ID, CHARACTER_ID, CURRENT_USER_MESSAGE);
        verifyNoMoreInteractions(builder);
    }

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

        verify(itemRepository, never()).save(any());
    }

    @Test
    void markMemoryItemDone_should_set_status_done_without_explicit_save() {
        MemoryItemEntity entity = MemoryItemTestFixtures.emotion(
                7L, 1L, "最近压力大", Instant.now());
        entity.setId(55L);
        when(itemRepository.findById(55L)).thenReturn(Optional.of(entity));

        api.markMemoryItemDone(55L);

        // managed entity 字段变更靠 JPA dirty checking 自动 flush，不显式 save
        assertThat(entity.getStatus()).isEqualTo(MemoryItemStatus.DONE);
        verify(itemRepository, never()).save(any());
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
