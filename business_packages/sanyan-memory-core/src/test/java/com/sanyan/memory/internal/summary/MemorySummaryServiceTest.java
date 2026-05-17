package com.sanyan.memory.internal.summary;

import com.sanyan.chat.SenderType;
import com.sanyan.chat.dto.MessageDto;
import com.sanyan.llm.LlmApi;
import com.sanyan.llm.LlmTaskType;
import com.sanyan.llm.dto.ChatMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plan 2 Task N2：MemorySummaryService 单元测试（Q3 适配新签名）。
 *
 * <p>Q3 task 改动：router 签名变更为 {@code chat(taskType, openAiMessages)}，service 通过
 * 真实 {@link PromptBuilder}（无状态 + 纯函数，可直接 new）拼好 messages 后传给 router。
 *
 * <p>S3 Phase 3 重构：mock {@link LlmApi} 取代 LLMProviderRouter；captor 类型相应改为
 * {@code List<ChatMessage>}。
 *
 * <p>验证：
 * <ul>
 *   <li>调 {@link LlmApi#chat} 时，task type 必须为 {@link LlmTaskType#BACKGROUND}
 *       （摘要属于后台任务，路由到 DeepSeek V4-Flash，低成本大吞吐）</li>
 *   <li>system prompt 模板必须包含关键约束词："小婉"、"100-200 字"、"对话纪要"——
 *       这些是产品定义的硬要求，prompt 改动出错时测试要能 catch 住</li>
 *   <li>service 返回值必须直接是 router 返回的 LLM 输出（service 不应对 LLM 输出做任何加工）</li>
 *   <li>PromptBuilder 输出的 messages 第一条必须是 system role + SYSTEM_PROMPT 内容</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class MemorySummaryServiceTest {

    @Mock
    private LlmApi llmApi;

    private MemorySummaryService service;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new MemorySummaryService(llmApi);
    }

    @Test
    void summarize_shouldRouteToBackgroundTaskType() {
        List<MessageDto> messages = buildFixtureMessages(30);
        when(llmApi.chat(any(LlmTaskType.class), any())).thenReturn("摘要内容");

        service.summarize(messages);

        ArgumentCaptor<LlmTaskType> taskTypeCaptor = ArgumentCaptor.forClass(LlmTaskType.class);
        verify(llmApi).chat(taskTypeCaptor.capture(), any());
        assertThat(taskTypeCaptor.getValue())
                .as("摘要属于后台任务，必须路由到 BACKGROUND（DeepSeek V4-Flash）")
                .isEqualTo(LlmTaskType.BACKGROUND);
    }

    @Test
    void summarize_shouldPassMessagesViaPromptBuilder() {
        List<MessageDto> messages = buildFixtureMessages(30);
        when(llmApi.chat(any(LlmTaskType.class), any())).thenReturn("摘要内容");

        service.summarize(messages);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmApi).chat(eq(LlmTaskType.BACKGROUND), captor.capture());
        List<ChatMessage> sent = captor.getValue();
        // 1 system + 30 history
        assertThat(sent).hasSize(31);
        assertThat(sent.get(0).role()).isEqualTo("system");
        // 后续 30 条历史消息按 user/assistant role 映射
        assertThat(sent.get(1).role()).isEqualTo("user");
        assertThat(sent.get(2).role()).isEqualTo("assistant");
    }

    @Test
    void summarize_systemPromptShouldContainCharacterNameXiaoWan() {
        List<MessageDto> messages = buildFixtureMessages(30);
        when(llmApi.chat(any(LlmTaskType.class), any())).thenReturn("摘要内容");

        service.summarize(messages);

        String systemMessageContent = captureSystemMessageContent();
        assertThat(systemMessageContent)
                .as("prompt 模板必须包含 AI 角色名「小婉」")
                .contains("小婉");
    }

    @Test
    void summarize_systemPromptShouldContainLengthConstraint() {
        List<MessageDto> messages = buildFixtureMessages(30);
        when(llmApi.chat(any(LlmTaskType.class), any())).thenReturn("摘要内容");

        service.summarize(messages);

        assertThat(captureSystemMessageContent())
                .as("prompt 模板必须约束摘要长度为 100-200 字")
                .contains("100-200 字");
    }

    @Test
    void summarize_systemPromptShouldContainSummaryKeyword() {
        List<MessageDto> messages = buildFixtureMessages(30);
        when(llmApi.chat(any(LlmTaskType.class), any())).thenReturn("摘要内容");

        service.summarize(messages);

        assertThat(captureSystemMessageContent())
                .as("prompt 模板必须出现「对话纪要」这个产品定义的输出形式词")
                .contains("对话纪要");
    }

    @Test
    void summarize_shouldReturnRouterOutputUnchanged() {
        List<MessageDto> messages = buildFixtureMessages(30);
        String fakeLlmOutput = "用户聊到了周三的技术面试，情绪比较焦虑，希望小婉帮忙加油打气。";
        when(llmApi.chat(eq(LlmTaskType.BACKGROUND), any())).thenReturn(fakeLlmOutput);

        String result = service.summarize(messages);

        assertThat(result)
                .as("service 不应对 LLM 输出做任何加工，直接返回")
                .isEqualTo(fakeLlmOutput);
    }

    private String captureSystemMessageContent() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmApi).chat(eq(LlmTaskType.BACKGROUND), captor.capture());
        return captor.getValue().get(0).content();
    }

    /**
     * 构造指定条数的 fixture 消息列表（30 条 = 触发摘要的阈值）。
     *
     * <p>本测试不复用 sanyan-business test 源里的 {@code MessageTestFixtures}——跨模块 src/test
     * 默认不可见（除非 test-jar），且 service 测试只关心传给 router 的参数对象引用，
     * 消息内容本身不参与断言，所以本地构造足够。
     */
    private List<MessageDto> buildFixtureMessages(int count) {
        List<MessageDto> messages = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            messages.add(new MessageDto(
                    (long) i,
                    1L,
                    i % 2 == 0 ? SenderType.USER : SenderType.AI,
                    i % 2 == 0 ? "用户消息 " + i : "AI 回复 " + i,
                    null));
        }
        return messages;
    }
}
