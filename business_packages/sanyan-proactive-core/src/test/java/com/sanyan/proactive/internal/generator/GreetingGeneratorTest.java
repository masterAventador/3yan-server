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
class GreetingGeneratorTest {

    @Mock LlmApi llmApi;
    ProactivePromptBuilder promptBuilder = new ProactivePromptBuilder(java.time.Clock.systemDefaultZone());

    private GreetingGenerator generator() {
        return new GreetingGenerator(llmApi, promptBuilder);
    }

    private GenerateContext ctx(String timeOfDay) {
        RelationshipDto rel = new RelationshipDto(1L, 1L, 250, 1, "朋友", 300, 0.5);
        return new GenerateContext(1L, 1L, rel,
                "当前关系阶段：朋友。称呼用户用：你。语调：自然。",
                MemoryContext.EMPTY, Map.of("timeOfDay", timeOfDay), List.of());
    }

    @Test
    void supportsType_should_be_a_greeting() {
        assertThat(generator().supportsType()).isEqualTo(EventType.A_GREETING);
    }

    @Test
    void generate_morning_should_call_user_facing_llm_and_return_segments() {
        when(llmApi.chat(eq(LlmTaskType.USER_FACING), any())).thenReturn("醒啦？今天也要加油哦");

        List<String> out = generator().generate(ctx("morning"));

        assertThat(out).containsExactly("醒啦？今天也要加油哦");
        verify(llmApi).chat(eq(LlmTaskType.USER_FACING), any());
    }

    @Test
    void generate_should_pass_different_scene_instruction_for_morning_vs_night() {
        when(llmApi.chat(eq(LlmTaskType.USER_FACING), any())).thenReturn("x");

        ArgumentCaptor<List<ChatMessage>> cap = ArgumentCaptor.forClass(List.class);

        generator().generate(ctx("morning"));
        verify(llmApi).chat(eq(LlmTaskType.USER_FACING), cap.capture());
        String morningUser = cap.getValue().get(cap.getValue().size() - 1).content();

        generator().generate(ctx("night"));
        verify(llmApi, org.mockito.Mockito.times(2)).chat(eq(LlmTaskType.USER_FACING), cap.capture());
        String nightUser = cap.getValue().get(cap.getValue().size() - 1).content();

        assertThat(morningUser).contains("早");
        assertThat(nightUser).contains("晚");
        assertThat(morningUser).isNotEqualTo(nightUser);
    }
}
