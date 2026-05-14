package com.sanyan.memory.internal.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanyan.chat.internal.MessageEntity;
import com.sanyan.chat.internal.SenderType;
import com.sanyan.common.error.BusinessException;
import com.sanyan.llm.internal.LLMProviderRouter;
import com.sanyan.llm.internal.LLMTaskType;
import com.sanyan.llm.internal.LlmErrCode;
import com.sanyan.llm.internal.PromptBuilder;
import com.sanyan.memory.internal.profile.dto.ProfileExtraction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plan 2 Task O2：MemoryProfileExtractService 单元测试（Q3 适配新签名）。
 *
 * <p>Q3 task 改动：router 签名变更为 {@code chat(taskType, openAiMessages)}，service 通过真实
 * {@link PromptBuilder}（无状态 + 纯函数，可直接 new）拼好 messages 后传给 router。
 *
 * <p>验证：
 * <ul>
 *   <li>合法 JSON → 正确解析为 {@link ProfileExtraction}（含全部 4 个字段）</li>
 *   <li>字段部分缺失 JSON → 仍正常返回（缺失字段为 null）</li>
 *   <li>完全非法 JSON → 返回 {@code null} + log.warn（不抛异常）</li>
 *   <li>LLM 返回空字符串 / blank → 返回 {@code null}</li>
 *   <li>LLM 调用本身抛 {@link BusinessException} → 捕获 + 返回 {@code null}</li>
 *   <li>路由到 {@link LLMTaskType#BACKGROUND}（后台异步抽取走 DeepSeek V4-Flash）</li>
 *   <li>system prompt 必须包含 schema 关键字段名（守护 prompt 模板）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class MemoryProfileExtractServiceTest {

    @Mock
    private LLMProviderRouter llmRouter;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PromptBuilder promptBuilder = new PromptBuilder();

    private MemoryProfileExtractService newService() {
        return new MemoryProfileExtractService(llmRouter, objectMapper, promptBuilder);
    }

    @Test
    void extract_shouldParseValidJsonWithAllFields() {
        MemoryProfileExtractService service = newService();
        String llmJson = """
                {
                  "basic_info_updates": {"occupation": "前端工程师", "age": null, "hometown": "杭州"},
                  "preferences_appended": {"foods": ["寿司"], "movies": [], "colors": [], "music": []},
                  "events_appended": [{"type": "interview", "content": "周三技术面试", "date": null}],
                  "emotion_signal": "焦虑"
                }
                """;
        when(llmRouter.chat(eq(LLMTaskType.BACKGROUND), any())).thenReturn(llmJson);

        ProfileExtraction result = service.extract(buildFixtureMessages(3));

        assertThat(result).isNotNull();
        assertThat(result.basicInfoUpdates())
                .as("basic_info_updates snake_case 必须绑定到 camelCase 字段")
                .containsEntry("occupation", "前端工程师")
                .containsEntry("hometown", "杭州");
        assertThat(result.preferencesAppended())
                .containsEntry("foods", List.of("寿司"));
        assertThat(result.eventsAppended()).hasSize(1);
        assertThat(result.eventsAppended().get(0))
                .containsEntry("type", "interview")
                .containsEntry("content", "周三技术面试");
        assertThat(result.emotionSignal()).isEqualTo("焦虑");
    }

    @Test
    void extract_shouldParsePartialFieldsAsNullable() {
        MemoryProfileExtractService service = newService();
        // 只有 emotion_signal，其余字段缺失
        String llmJson = """
                {"emotion_signal": "平静"}
                """;
        when(llmRouter.chat(eq(LLMTaskType.BACKGROUND), any())).thenReturn(llmJson);

        ProfileExtraction result = service.extract(buildFixtureMessages(3));

        assertThat(result).as("部分字段缺失也要正常返回，不抛异常").isNotNull();
        assertThat(result.emotionSignal()).isEqualTo("平静");
        assertThat(result.basicInfoUpdates()).isNull();
        assertThat(result.preferencesAppended()).isNull();
        assertThat(result.eventsAppended()).isNull();
    }

    @Test
    void extract_shouldReturnNullForInvalidJson() {
        MemoryProfileExtractService service = newService();
        String invalidJson = "this is not json at all { broken";
        when(llmRouter.chat(eq(LLMTaskType.BACKGROUND), any())).thenReturn(invalidJson);

        ProfileExtraction result = service.extract(buildFixtureMessages(3));

        assertThat(result)
                .as("非法 JSON 必须返回 null，不抛异常（让 O4 listener 跳过即可）")
                .isNull();
    }

    @Test
    void extract_shouldReturnNullForBlankOutput() {
        MemoryProfileExtractService service = newService();
        when(llmRouter.chat(eq(LLMTaskType.BACKGROUND), any())).thenReturn("   ");

        ProfileExtraction result = service.extract(buildFixtureMessages(3));

        assertThat(result).as("LLM 返回空 / blank 必须返回 null").isNull();
    }

    @Test
    void extract_shouldReturnNullWhenLlmThrowsBusinessException() {
        MemoryProfileExtractService service = newService();
        when(llmRouter.chat(eq(LLMTaskType.BACKGROUND), any()))
                .thenThrow(new BusinessException(LlmErrCode.LLM_UPSTREAM_UNAVAILABLE));

        ProfileExtraction result = service.extract(buildFixtureMessages(3));

        assertThat(result)
                .as("LLM 上游异常时 service 必须吞掉异常返回 null（容错链路，让 O4 listener 跳过）")
                .isNull();
    }

    @Test
    void extract_shouldReturnNullForEmptyMessages() {
        MemoryProfileExtractService service = newService();

        assertThat(service.extract(null)).as("null 输入 → null").isNull();
        assertThat(service.extract(List.of())).as("空列表输入 → null").isNull();
    }

    @Test
    void extract_shouldRouteToBackgroundTaskType() {
        MemoryProfileExtractService service = newService();
        when(llmRouter.chat(any(LLMTaskType.class), any())).thenReturn("{}");

        service.extract(buildFixtureMessages(3));

        ArgumentCaptor<LLMTaskType> taskTypeCaptor = ArgumentCaptor.forClass(LLMTaskType.class);
        verify(llmRouter).chat(taskTypeCaptor.capture(), any());
        assertThat(taskTypeCaptor.getValue())
                .as("档案抽取属于后台任务，必须路由到 BACKGROUND（DeepSeek V4-Flash）")
                .isEqualTo(LLMTaskType.BACKGROUND);
    }

    @Test
    void extract_systemPromptShouldContainSchemaKeyFields() {
        MemoryProfileExtractService service = newService();
        when(llmRouter.chat(any(LLMTaskType.class), any())).thenReturn("{}");

        service.extract(buildFixtureMessages(3));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, String>>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmRouter).chat(eq(LLMTaskType.BACKGROUND), captor.capture());
        String prompt = captor.getValue().get(0).get("content");
        assertThat(prompt)
                .as("prompt 模板必须列出 JSON schema 的 4 个关键字段")
                .contains("basic_info_updates")
                .contains("preferences_appended")
                .contains("events_appended")
                .contains("emotion_signal");
        assertThat(prompt)
                .as("prompt 模板必须强调只输出 JSON（否则 Jackson 解析会失败）")
                .contains("JSON");
    }

    /**
     * 构造指定条数的 fixture 消息列表。和 N2 测试做法一致，本地构造即可。
     */
    private List<MessageEntity> buildFixtureMessages(int count) {
        List<MessageEntity> messages = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            MessageEntity m = new MessageEntity();
            m.setUserId(1L);
            m.setSenderType(i % 2 == 0 ? SenderType.USER : SenderType.AI);
            m.setContent(i % 2 == 0 ? "用户消息 " + i : "AI 回复 " + i);
            messages.add(m);
        }
        return messages;
    }
}
