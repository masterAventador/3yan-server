package com.sanyan.memory.internal.summary;

import com.sanyan.chat.internal.MessageEntity;
import com.sanyan.chat.internal.SenderType;
import com.sanyan.llm.internal.LLMProviderRouter;
import com.sanyan.llm.internal.LLMTaskType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plan 2 Task N2：MemorySummaryService 单元测试。
 *
 * <p>纯 Mockito 单测，验证：
 * <ul>
 *   <li>调 {@link LLMProviderRouter#chat} 时，task type 必须为 {@link LLMTaskType#BACKGROUND}
 *       （摘要属于后台任务，路由到 DeepSeek V4-Flash，低成本大吞吐）</li>
 *   <li>system prompt 模板必须包含关键约束词："小婉"、"100-200 字"、"对话纪要"——
 *       这些是产品定义的硬要求，prompt 改动出错时测试要能 catch 住</li>
 *   <li>service 返回值必须直接是 router 返回的 LLM 输出（service 不应对 LLM 输出做任何加工）</li>
 *   <li>传入的 messages 列表必须原样传给 router（router 内部会做 OpenAI 格式组装，service 不要重复加工）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class MemorySummaryServiceTest {

    @Mock
    private LLMProviderRouter llmRouter;

    @InjectMocks
    private MemorySummaryService service;

    @Test
    void summarize_shouldRouteToBackgroundTaskType() {
        List<MessageEntity> messages = buildFixtureMessages(30);
        when(llmRouter.chat(any(LLMTaskType.class), anyString(), any())).thenReturn("摘要内容");

        service.summarize(messages);

        ArgumentCaptor<LLMTaskType> taskTypeCaptor = ArgumentCaptor.forClass(LLMTaskType.class);
        verify(llmRouter).chat(taskTypeCaptor.capture(), anyString(), any());
        assertThat(taskTypeCaptor.getValue())
                .as("摘要属于后台任务，必须路由到 BACKGROUND（DeepSeek V4-Flash）")
                .isEqualTo(LLMTaskType.BACKGROUND);
    }

    @Test
    void summarize_shouldPassMessagesUnchangedToRouter() {
        List<MessageEntity> messages = buildFixtureMessages(30);
        when(llmRouter.chat(any(LLMTaskType.class), anyString(), any())).thenReturn("摘要内容");

        service.summarize(messages);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MessageEntity>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(llmRouter).chat(eq(LLMTaskType.BACKGROUND), anyString(), messagesCaptor.capture());
        assertThat(messagesCaptor.getValue())
                .as("service 不应该对 messages 做任何加工，原样传给 router")
                .isSameAs(messages);
    }

    @Test
    void summarize_systemPromptShouldContainCharacterNameXiaoWan() {
        List<MessageEntity> messages = buildFixtureMessages(30);
        when(llmRouter.chat(any(LLMTaskType.class), anyString(), any())).thenReturn("摘要内容");

        service.summarize(messages);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmRouter).chat(eq(LLMTaskType.BACKGROUND), promptCaptor.capture(), any());
        assertThat(promptCaptor.getValue())
                .as("prompt 模板必须包含 AI 角色名「小婉」")
                .contains("小婉");
    }

    @Test
    void summarize_systemPromptShouldContainLengthConstraint() {
        List<MessageEntity> messages = buildFixtureMessages(30);
        when(llmRouter.chat(any(LLMTaskType.class), anyString(), any())).thenReturn("摘要内容");

        service.summarize(messages);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmRouter).chat(eq(LLMTaskType.BACKGROUND), promptCaptor.capture(), any());
        assertThat(promptCaptor.getValue())
                .as("prompt 模板必须约束摘要长度为 100-200 字")
                .contains("100-200 字");
    }

    @Test
    void summarize_systemPromptShouldContainSummaryKeyword() {
        List<MessageEntity> messages = buildFixtureMessages(30);
        when(llmRouter.chat(any(LLMTaskType.class), anyString(), any())).thenReturn("摘要内容");

        service.summarize(messages);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmRouter).chat(eq(LLMTaskType.BACKGROUND), promptCaptor.capture(), any());
        assertThat(promptCaptor.getValue())
                .as("prompt 模板必须出现「对话纪要」这个产品定义的输出形式词")
                .contains("对话纪要");
    }

    @Test
    void summarize_shouldReturnRouterOutputUnchanged() {
        List<MessageEntity> messages = buildFixtureMessages(30);
        String fakeLlmOutput = "用户聊到了周三的技术面试，情绪比较焦虑，希望小婉帮忙加油打气。";
        when(llmRouter.chat(eq(LLMTaskType.BACKGROUND), anyString(), any())).thenReturn(fakeLlmOutput);

        String result = service.summarize(messages);

        assertThat(result)
                .as("service 不应对 LLM 输出做任何加工，直接返回")
                .isEqualTo(fakeLlmOutput);
    }

    /**
     * 构造指定条数的 fixture 消息列表（30 条 = 触发摘要的阈值）。
     *
     * <p>本测试不复用 sanyan-business test 源里的 {@code MessageTestFixtures}——跨模块 src/test
     * 默认不可见（除非 test-jar），且 service 测试只关心传给 router 的参数对象引用，
     * 消息内容本身不参与断言，所以本地构造足够。
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
