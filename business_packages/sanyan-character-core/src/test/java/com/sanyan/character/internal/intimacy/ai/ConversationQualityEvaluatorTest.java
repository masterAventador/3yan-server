package com.sanyan.character.internal.intimacy.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanyan.character.internal.intimacy.IntimacyProperties;
import com.sanyan.chat.dto.MessageDto;
import com.sanyan.llm.LlmApi;
import com.sanyan.llm.LlmTaskType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationQualityEvaluatorTest {

    @Mock
    LlmApi llmApi;

    IntimacyProperties props;
    ConversationQualityEvaluator evaluator;

    @BeforeEach
    void setup() {
        props = new IntimacyProperties();  // qualityMaxScore 默认 20
        evaluator = new ConversationQualityEvaluator(llmApi, new ObjectMapper(), props);
    }

    @Test
    void should_parse_score_from_llm_json_response() {
        when(llmApi.chat(eq(LlmTaskType.BACKGROUND), any()))
                .thenReturn("{\"score\":15,\"reason\":\"深度分享情绪\"}");

        var result = evaluator.score(List.of(userMsg("我今天有点难过……")));
        assertThat(result.score()).isEqualTo(15);
        assertThat(result.reason()).isEqualTo("深度分享情绪");
    }

    @Test
    void should_cap_score_at_max() {
        when(llmApi.chat(any(), any())).thenReturn("{\"score\":999,\"reason\":\"x\"}");
        assertThat(evaluator.score(List.of()).score()).isEqualTo(20);
    }

    @Test
    void should_clamp_negative_score() {
        when(llmApi.chat(any(), any())).thenReturn("{\"score\":-5,\"reason\":\"x\"}");
        assertThat(evaluator.score(List.of()).score()).isZero();
    }

    @Test
    void should_return_zero_on_parse_failure() {
        when(llmApi.chat(any(), any())).thenReturn("not json");
        var r = evaluator.score(List.of());
        assertThat(r.score()).isZero();
        assertThat(r.reason()).isEqualTo("评估失败");
    }

    @Test
    void should_return_zero_when_llm_throws() {
        when(llmApi.chat(any(), any())).thenThrow(new RuntimeException("llm down"));
        var r = evaluator.score(List.of());
        assertThat(r.score()).isZero();
    }

    private MessageDto userMsg(String content) {
        return new MessageDto(1L, 1L, "user", content, LocalDateTime.now());
    }
}
