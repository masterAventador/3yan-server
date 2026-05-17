package com.sanyan.memory.api;

import com.sanyan.memory.MemoryApi;
import com.sanyan.memory.dto.MemoryContext;
import com.sanyan.memory.internal.orchestrator.MemoryContextBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Plan 2 Task Q2：MemoryApiImpl 单元测试。
 *
 * <p>{@link MemoryApiImpl} 是 -api 模块 {@link MemoryApi} 契约在 -core 侧的薄壳实现，
 * 只做一件事：把入参完整透传给 {@link MemoryContextBuilder#build}，把返回值原样返回。
 *
 * <p>本测试用 Mockito 把 builder 整个 mock 掉，验证两点：
 * <ol>
 *   <li>builder 返回 {@code null} 时 ApiImpl 也返回 {@code null}（不要私自包成 EMPTY）</li>
 *   <li>builder 返回非空 MemoryContext 时 ApiImpl 原样透传，且参数顺序正确</li>
 * </ol>
 *
 * <p>三层组合细节由 {@code MemoryContextBuilderTest}（Q1）覆盖，这里只验"薄壳委托"语义。
 */
@ExtendWith(MockitoExtension.class)
class MemoryApiImplTest {

    private static final Long USER_ID = 42L;
    private static final Long CHARACTER_ID = 7L;
    private static final String CURRENT_USER_MESSAGE = "今天我去吃了火锅";

    @Mock
    MemoryContextBuilder builder;

    @InjectMocks
    MemoryApiImpl api;

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
}
