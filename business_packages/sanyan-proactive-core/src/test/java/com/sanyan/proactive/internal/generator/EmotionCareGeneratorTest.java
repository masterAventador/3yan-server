package com.sanyan.proactive.internal.generator;

import com.sanyan.character.dto.RelationshipDto;
import com.sanyan.llm.LlmApi;
import com.sanyan.llm.LlmTaskType;
import com.sanyan.llm.dto.ChatMessage;
import com.sanyan.memory.MemoryApi;
import com.sanyan.memory.dto.MemoryContext;
import com.sanyan.memory.dto.MemoryItemDto;
import com.sanyan.proactive.internal.EventType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
        when(memoryApi.getMemoryItem(99L)).thenThrow(new RuntimeException("not found"));

        List<String> out = generator().generate(ctx(99L));

        assertThat(out).isEmpty();
    }
}
