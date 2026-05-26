package com.sanyan.proactive.internal.generator;

import com.sanyan.character.dto.RelationshipDto;
import com.sanyan.common.error.BusinessException;
import com.sanyan.llm.LlmApi;
import com.sanyan.llm.LlmTaskType;
import com.sanyan.llm.dto.ChatMessage;
import com.sanyan.memory.MemoryApi;
import com.sanyan.memory.dto.MemoryContext;
import com.sanyan.memory.dto.MemoryItemDto;
import com.sanyan.proactive.internal.EventType;
import com.sanyan.proactive.internal.ProactiveErrCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmotionCareGeneratorTest {

    @Mock LlmApi llmApi;
    @Mock MemoryApi memoryApi;
    ProactivePromptBuilder promptBuilder = new ProactivePromptBuilder();

    private EmotionCareGenerator generator() {
        return new EmotionCareGenerator(llmApi, memoryApi, promptBuilder);
    }

    private GenerateContext ctx(long itemId) {
        RelationshipDto rel = new RelationshipDto(1L, 1L, 250, 1, "朋友", 300, 0.5);
        return new GenerateContext(1L, 1L, rel, "", MemoryContext.EMPTY,
                Map.of("memoryItemId", itemId));
    }

    @Test
    void supportsType_should_be_d_emotion_care() {
        assertThat(generator().supportsType()).isEqualTo(EventType.D_EMOTION_CARE);
    }

    @Test
    void generate_should_instruct_indirect_care_with_emotion_context() {
        when(memoryApi.getMemoryItem(9L)).thenReturn(new MemoryItemDto(
                9L, 1L, 1L, "EMOTION", "最近工作压力大、很焦虑", Instant.now(), "PENDING"));
        when(llmApi.chat(eq(LlmTaskType.USER_FACING), any())).thenReturn("今天有没有好好吃饭呀");

        ArgumentCaptor<List<ChatMessage>> cap = ArgumentCaptor.forClass(List.class);
        List<String> out = generator().generate(ctx(9L));

        assertThat(out).containsExactly("今天有没有好好吃饭呀");
        verify(llmApi).chat(eq(LlmTaskType.USER_FACING), cap.capture());
        String user = cap.getValue().get(cap.getValue().size() - 1).content();
        assertThat(user).contains("最近工作压力大、很焦虑");
        assertThat(user).contains("间接");        // 关键约束：间接关心
        assertThat(user).contains("不要直接");     // 不直说"你昨天难过"
    }

    @Test
    void generate_should_return_empty_list_when_memory_item_not_found() {
        // 条目不存在：memory 域抛 BusinessException（此处用 PROACTIVE_EVENT_NOT_FOUND 模拟同类型异常），
        // catch (BusinessException) 静默降级返回空 List
        when(memoryApi.getMemoryItem(99L))
                .thenThrow(new BusinessException(ProactiveErrCode.PROACTIVE_EVENT_NOT_FOUND));

        List<String> out = generator().generate(ctx(99L));

        assertThat(out).isEmpty();
        verify(llmApi, never()).chat(any(), any());
    }

    @Test
    void generate_should_propagate_llm_runtime_exception() {
        // LLM 调用故障：RuntimeException 不被 BusinessException catch 吞，应往上抛
        when(memoryApi.getMemoryItem(9L)).thenReturn(new MemoryItemDto(
                9L, 1L, 1L, "EMOTION", "测试情绪", Instant.now(), "PENDING"));
        when(llmApi.chat(any(), any())).thenThrow(new RuntimeException("LLM timeout"));

        assertThatThrownBy(() -> generator().generate(ctx(9L)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("LLM timeout");
    }
}
