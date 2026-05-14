package com.sanyan.llm.internal;

import com.sanyan.character.internal.AiCharacterEntity;
import com.sanyan.character.internal.AiCharacterTestFixtures;
import com.sanyan.chat.internal.MessageEntity;
import com.sanyan.chat.internal.MessageRepository;
import com.sanyan.chat.internal.SenderType;
import com.sanyan.memory.MemoryConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task M3：AiService 单元测试（重写）。
 *
 * <p>Plan 1 时代直接 mock RestTemplate 走豆包；M3 把豆包抽到 {@link DoubaoAdapter}，
 * 路由层 {@link LLMProviderRouter} 接管 task type → provider 的选择。
 * 现在 AiService 退化为"装配 system prompt + 拉短期上下文 + 委托给 router"的薄编排层，
 * 测试只需 mock router 行为即可。
 */
@ExtendWith(MockitoExtension.class)
class AiServiceTest {

    @Mock MessageRepository messageRepository;
    @Mock LLMProviderRouter llmRouter;

    private AiService service;

    private static final String SYSTEM_PROMPT_MARKER = "MARKER_FROM_RESOURCE_FILE";

    @BeforeEach
    void setUp() {
        // Q3 起 AiService 直接持有 PromptBuilder（无状态 Spring Bean，可在测试里直接 new）
        service = new AiService(messageRepository, llmRouter, new PromptBuilder());
        Resource resource = new ByteArrayResource(
                SYSTEM_PROMPT_MARKER.getBytes(StandardCharsets.UTF_8));
        ReflectionTestUtils.setField(service, "systemPromptResource", resource);
        ReflectionTestUtils.invokeMethod(service, "loadSystemPrompt");
    }

    @Test
    void chat_shouldDelegateToRouterWithUserFacingTaskType() {
        when(messageRepository.findByUserIdOrderByIdDesc(anyLong(), any()))
                .thenReturn(List.of());
        when(llmRouter.chat(eq(LLMTaskType.USER_FACING), any()))
                .thenReturn("hello-from-router");

        AiCharacterEntity character = AiCharacterTestFixtures.xiaowan();
        String reply = service.chat(character, 1L);

        assertThat(reply).isEqualTo("hello-from-router");
        verify(llmRouter).chat(eq(LLMTaskType.USER_FACING), any());
    }

    @Test
    void chat_shouldPassSystemPromptLoadedFromResourceToRouter() {
        when(messageRepository.findByUserIdOrderByIdDesc(anyLong(), any()))
                .thenReturn(List.of());
        when(llmRouter.chat(eq(LLMTaskType.USER_FACING), any()))
                .thenReturn("ok");

        service.chat(AiCharacterTestFixtures.xiaowan(), 1L);

        // Q3：AiService 走 PromptBuilder 拼成 OpenAI messages，第一条必须是包含资源文件人设的 system 消息
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, String>>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmRouter).chat(eq(LLMTaskType.USER_FACING), captor.capture());
        List<Map<String, String>> sent = captor.getValue();
        assertThat(sent).isNotEmpty();
        assertThat(sent.get(0)).containsEntry("role", "system");
        assertThat(sent.get(0).get("content"))
                .as("system prompt 必须来自资源文件，且追加了当前时间")
                .contains(SYSTEM_PROMPT_MARKER)
                .contains("[当前时间]");
    }

    @Test
    void chat_shouldPassRecentMessagesInChronologicalOrder() {
        MessageEntity older = new MessageEntity();
        older.setSenderType(SenderType.USER);
        older.setContent("你好");
        MessageEntity newer = new MessageEntity();
        newer.setSenderType(SenderType.AI);
        newer.setContent("你好呀");

        // Repository 按 id desc 排序返回（最新在前），AiService 必须 reverse 成时间顺序传给 PromptBuilder
        // 用可变 ArrayList——Collections.reverse 不能作用于 List.of() 的不可变列表
        when(messageRepository.findByUserIdOrderByIdDesc(eq(1L), any()))
                .thenReturn(new ArrayList<>(List.of(newer, older)));
        when(llmRouter.chat(eq(LLMTaskType.USER_FACING), any()))
                .thenReturn("ack");

        service.chat(AiCharacterTestFixtures.xiaowan(), 1L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, String>>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmRouter).chat(eq(LLMTaskType.USER_FACING), captor.capture());
        List<Map<String, String>> sent = captor.getValue();
        // 1 system + 2 history
        assertThat(sent).hasSize(3);
        assertThat(sent.get(1)).containsEntry("role", "user").containsEntry("content", "你好");
        assertThat(sent.get(2)).containsEntry("role", "assistant").containsEntry("content", "你好呀");
    }

    @Test
    void chat_shouldUseShortTermWindowSizeFromMemoryConstants() {
        when(messageRepository.findByUserIdOrderByIdDesc(eq(1L), any()))
                .thenReturn(List.of());
        when(llmRouter.chat(eq(LLMTaskType.USER_FACING), any()))
                .thenReturn("ok");

        service.chat(AiCharacterTestFixtures.xiaowan(), 1L);

        ArgumentCaptor<PageRequest> pageCaptor = ArgumentCaptor.forClass(PageRequest.class);
        verify(messageRepository).findByUserIdOrderByIdDesc(eq(1L), pageCaptor.capture());
        // Q3 回归断言：必须严格 == MemoryConstants.SHORT_TERM_WINDOW_SIZE，防止后续有人改回字面量 20 或 32
        assertThat(pageCaptor.getValue().getPageSize())
                .as("短期窗口大小必须从 MemoryConstants.SHORT_TERM_WINDOW_SIZE 取，禁止字面量")
                .isEqualTo(MemoryConstants.SHORT_TERM_WINDOW_SIZE);
        assertThat(pageCaptor.getValue().getPageNumber()).isZero();
    }

    @Test
    void productionSystemPromptResource_containsRelationshipBoundaries() throws IOException {
        String prompt = Files.readString(
                Path.of("src/main/resources/prompts/xiaowan-system.md"));
        assertThat(prompt)
                .as("人设资源文件必须包含小婉介绍与三类边界")
                .contains("你是小婉")
                .contains("[能力边界]")
                .contains("[关系边界]")
                .contains("纯线上聊天")
                .contains("线下");
    }
}
