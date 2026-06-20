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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventFollowupGeneratorTest {

    @Mock LlmApi llmApi;
    @Mock MemoryApi memoryApi;
    ProactivePromptBuilder promptBuilder = new ProactivePromptBuilder(java.time.Clock.systemDefaultZone());

    private EventFollowupGenerator generator() {
        return new EventFollowupGenerator(llmApi, memoryApi, promptBuilder);
    }

    private GenerateContext ctx(long itemId) {
        RelationshipDto rel = new RelationshipDto(1L, 1L, 250, 1, "朋友", 300, 0.5);
        return new GenerateContext(1L, 1L, rel, "", MemoryContext.EMPTY,
                Map.of("memoryItemId", itemId));
    }

    @Test
    void supportsType_should_be_c_event_followup() {
        assertThat(generator().supportsType()).isEqualTo(EventType.C_EVENT_FOLLOWUP);
    }

    @Test
    void generate_should_inject_memory_item_content_into_scene() {
        when(memoryApi.getMemoryItem(7L)).thenReturn(new MemoryItemDto(
                7L, 1L, 1L, "PLAN_EVENT", "周三有一场面试", Instant.now(), "PENDING"));
        when(llmApi.chat(eq(LlmTaskType.USER_FACING), any())).thenReturn("面试顺利吗？");

        ArgumentCaptor<List<ChatMessage>> cap = ArgumentCaptor.forClass(List.class);
        List<String> out = generator().generate(ctx(7L));

        assertThat(out).containsExactly("面试顺利吗？");
        verify(llmApi).chat(eq(LlmTaskType.USER_FACING), cap.capture());
        String user = cap.getValue().get(cap.getValue().size() - 1).content();
        assertThat(user).contains("周三有一场面试");
    }

    @Test
    void generate_should_return_empty_when_memory_item_not_found() {
        when(memoryApi.getMemoryItem(404L))
                .thenThrow(new BusinessException(ProactiveErrCode.PROACTIVE_EVENT_NOT_FOUND));

        List<String> out = generator().generate(ctx(404L));

        assertThat(out).isEmpty();
        verify(llmApi, never()).chat(any(), any());
    }
}
