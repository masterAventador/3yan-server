package com.sanyan.proactive.internal.generator;

import com.sanyan.character.dto.RelationshipDto;
import com.sanyan.llm.LlmApi;
import com.sanyan.llm.LlmTaskType;
import com.sanyan.llm.dto.ChatMessage;
import com.sanyan.memory.dto.MemoryContext;
import com.sanyan.proactive.internal.EventType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecallGeneratorTest {

    @Mock LlmApi llmApi;
    ProactivePromptBuilder promptBuilder = new ProactivePromptBuilder();

    private RecallGenerator generator() {
        return new RecallGenerator(llmApi, promptBuilder);
    }

    private GenerateContext ctx(int level) {
        RelationshipDto rel = new RelationshipDto(1L, 1L, 250, 1, "朋友", 300, 0.5);
        return new GenerateContext(1L, 1L, rel, "", MemoryContext.EMPTY,
                Map.of("escalationLevel", level));
    }

    @Test
    void supportsType_should_be_b_recall() {
        assertThat(generator().supportsType()).isEqualTo(EventType.B_RECALL);
    }

    @Test
    void generate_should_use_distinct_tone_per_escalation_level() {
        when(llmApi.chat(eq(LlmTaskType.USER_FACING), any())).thenReturn("x");
        ArgumentCaptor<List<ChatMessage>> cap = ArgumentCaptor.forClass(List.class);

        generator().generate(ctx(0));
        generator().generate(ctx(1));
        generator().generate(ctx(2));

        verify(llmApi, org.mockito.Mockito.times(3)).chat(eq(LlmTaskType.USER_FACING), cap.capture());
        List<List<ChatMessage>> all = cap.getAllValues();
        String care = all.get(0).get(all.get(0).size() - 1).content();
        String coquetry = all.get(1).get(all.get(1).size() - 1).content();
        String possessive = all.get(2).get(all.get(2).size() - 1).content();

        assertThat(care).isNotEqualTo(coquetry);
        assertThat(coquetry).isNotEqualTo(possessive);
        assertThat(care).contains("关心");
        assertThat(coquetry).contains("撒娇");
        assertThat(possessive).contains("占有");
    }
}
