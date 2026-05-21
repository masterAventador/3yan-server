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
 * <p>Q3 task 改动：router 签名变更为 {@code chat(taskType, openAiMessages)}，service 拼好
 * messages 后传给 router。
 *
 * <p>S3 Phase 3 重构：mock {@link LlmApi} 取代 LLMProviderRouter；captor 类型相应改为
 * {@code List<ChatMessage>}。
 *
 * <p>S3 Phase 5 重构：service 不再依赖共享 PromptBuilder（已留在 chat-core/internal/），
 * 内联拼装 system prompt + history 形式的 {@code List<ChatMessage>}。
 *
 * <p>验证：
 * <ul>
 *   <li>调 {@link LlmApi#chat} 时，task type 必须为 {@link LlmTaskType#BACKGROUND}
 *       （摘要属于后台任务，路由到 DeepSeek V4-Flash，低成本大吞吐）</li>
 *   <li>system prompt 模板必须包含关键约束词："小婉"、"100-200 字"、"对话纪要"——
 *       这些是产品定义的硬要求，prompt 改动出错时测试要能 catch 住</li>
 *   <li>service 返回值必须直接是 router 返回的 LLM 输出（service 不应对 LLM 输出做任何加工）</li>
 *   <li>service 内联拼装的 messages 第一条必须是 system role + SYSTEM_PROMPT 内容</li>
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
    void summarize_shouldSendSystemPromptPlusSingleUserMessageContainingDialogue() {
        // 关键：对话历史**必须**作为单条 user message 的 content 嵌入，不能展开成多个
        // user/assistant turns——否则 LLM 会把它当作"正在进行的对话"接最后一条 user 消息的话
        // 而不是执行 system 指令做摘要（dogfood 实测出来的 bug，FAIL detail:
        //   summary_text 太短 (len=18), 内容: '月季和薄荷都是很适合阳台养的植物呢！'
        // ——AI 接了用户第 13 条消息"种了几盆绿植，月季和薄荷活得最好"）。
        List<MessageDto> messages = buildFixtureMessages(30);
        when(llmApi.chat(any(LlmTaskType.class), any())).thenReturn("摘要内容");

        service.summarize(messages);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmApi).chat(eq(LlmTaskType.BACKGROUND), captor.capture());
        List<ChatMessage> sent = captor.getValue();
        // 必须严格 2 条：system + user（对话历史嵌在 user message 的 content 里）
        assertThat(sent)
                .as("对话历史必须嵌入单条 user message，不能展开成 turns，否则 LLM 会接话不摘要")
                .hasSize(2);
        assertThat(sent.get(0).role()).isEqualTo("system");
        assertThat(sent.get(1).role())
                .as("第 2 条必须是 user role（带对话上下文的请求），不能是 assistant")
                .isEqualTo("user");
    }

    @Test
    void summarize_userMessageShouldEmbedAllDialogueTurnsWithRoleMarkers() {
        // 单条 user message 的 content 必须包含每条历史消息的内容 + 角色标记，
        // 让 LLM 能在文本层面理解谁说了什么——不是把消息列表展开成 chat turns。
        List<MessageDto> messages = buildFixtureMessages(4);
        when(llmApi.chat(any(LlmTaskType.class), any())).thenReturn("摘要内容");

        service.summarize(messages);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmApi).chat(eq(LlmTaskType.BACKGROUND), captor.capture());
        String userContent = captor.getValue().get(1).content();

        // user message content 必须含所有 4 条 fixture 消息
        assertThat(userContent).contains("用户消息 0");
        assertThat(userContent).contains("AI 回复 1");
        assertThat(userContent).contains("用户消息 2");
        assertThat(userContent).contains("AI 回复 3");
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
