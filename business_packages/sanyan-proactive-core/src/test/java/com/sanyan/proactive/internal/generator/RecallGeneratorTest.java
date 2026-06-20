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
    ProactivePromptBuilder promptBuilder = new ProactivePromptBuilder(java.time.Clock.systemDefaultZone());

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

    @Test
    void generate_should_fallback_to_level0_when_escalation_level_out_of_range() {
        // escalationLevel=99 越界 → 回落 level-0 关心语调，正常生成消息（warn 日志不 assert，避免引入额外测试库）
        when(llmApi.chat(eq(LlmTaskType.USER_FACING), any())).thenReturn("最近好吗");
        ArgumentCaptor<List<ChatMessage>> cap = ArgumentCaptor.forClass(List.class);

        List<String> out = generator().generate(ctx(99));

        assertThat(out).containsExactly("最近好吗");
        verify(llmApi).chat(eq(LlmTaskType.USER_FACING), cap.capture());
        String prompt = cap.getValue().get(cap.getValue().size() - 1).content();
        assertThat(prompt).contains("关心");    // 回落 level-0：使用关心语调
    }
}
