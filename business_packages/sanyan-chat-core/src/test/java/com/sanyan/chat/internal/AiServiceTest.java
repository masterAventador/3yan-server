package com.sanyan.chat.internal;

import com.sanyan.character.CharacterApi;
import com.sanyan.character.dto.AiCharacterDto;
import com.sanyan.chat.SenderType;
import com.sanyan.llm.LlmApi;
import com.sanyan.llm.LlmTaskType;
import com.sanyan.llm.dto.ChatMessage;
import com.sanyan.memory.MemoryApi;
import com.sanyan.memory.MemoryConstants;
import com.sanyan.memory.dto.MemoryContext;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task M3：AiService 单元测试（重写）。
 *
 * <p>Plan 1 时代直接 mock RestTemplate 走豆包；M3 把豆包抽到 DoubaoAdapter，
 * 路由层 LLMProviderRouter 接管 task type → provider 的选择。
 * 现在 AiService 退化为"装配 system prompt + 拉短期上下文 + 委托给 LlmApi"的薄编排层，
 * 测试只需 mock {@link LlmApi} 行为即可。
 *
 * <p>S3 Phase 3 重构：AiService 不再持有 LLMProviderRouter，改持 {@link LlmApi} 契约；
 * 本测试相应地 mock LlmApi 而非 router。
 */
@ExtendWith(MockitoExtension.class)
class AiServiceTest {

    @Mock MessageRepository messageRepository;
    @Mock LlmApi llmApi;
    @Mock MemoryApi memoryApi;
    @Mock CharacterApi characterApi;

    private AiService service;

    private static final String SYSTEM_PROMPT_MARKER = "MARKER_FROM_RESOURCE_FILE";
    private static final Long DEFAULT_CHARACTER_ID = 1L;

    @BeforeEach
    void setUp() {
        // Q3 起 AiService 直接持有 PromptBuilder（无状态 Spring Bean，可在测试里直接 new）。
        // Q4 起再注入 MemoryApi，主对话前调 getRelevantContext 拿长期记忆 context。
        // I3 起注入 CharacterApi，调 getStagePromptSegment 拼入 stage prompt。
        service = new AiService(messageRepository, llmApi, new PromptBuilder(), memoryApi, characterApi);
        Resource resource = new ByteArrayResource(
                SYSTEM_PROMPT_MARKER.getBytes(StandardCharsets.UTF_8));
        ReflectionTestUtils.setField(service, "systemPromptResource", resource);
        // Q4：MVP 单角色，默认 characterId = 1L，通过 @Value 注入，测试里用 ReflectionTestUtils 显式塞
        ReflectionTestUtils.setField(service, "defaultCharacterId", DEFAULT_CHARACTER_ID);
        ReflectionTestUtils.invokeMethod(service, "loadSystemPrompt");
    }

    @Test
    void chat_shouldDelegateToRouterWithUserFacingTaskType() {
        when(messageRepository.findByUserIdOrderByIdDesc(anyLong(), any()))
                .thenReturn(List.of());
        when(llmApi.chat(eq(LlmTaskType.USER_FACING), any()))
                .thenReturn("hello-from-router");

        AiCharacterDto character = xiaowanDto();
        String reply = service.chat(character, 1L);

        assertThat(reply).isEqualTo("hello-from-router");
        verify(llmApi).chat(eq(LlmTaskType.USER_FACING), any());
    }

    @Test
    void chat_shouldPassSystemPromptLoadedFromResourceToRouter() {
        when(messageRepository.findByUserIdOrderByIdDesc(anyLong(), any()))
                .thenReturn(List.of());
        when(llmApi.chat(eq(LlmTaskType.USER_FACING), any()))
                .thenReturn("ok");

        service.chat(xiaowanDto(), 1L);

        // Q3：AiService 走 PromptBuilder 拼成 OpenAI messages，第一条必须是包含资源文件人设的 system 消息
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmApi).chat(eq(LlmTaskType.USER_FACING), captor.capture());
        List<ChatMessage> sent = captor.getValue();
        assertThat(sent).isNotEmpty();
        assertThat(sent.get(0).role()).isEqualTo("system");
        assertThat(sent.get(0).content())
                .as("system prompt 必须来自资源文件，且追加了当前时间和时间感知引导")
                .contains(SYSTEM_PROMPT_MARKER)
                .contains("[当前时间]")
                .contains("时间感知")
                .contains("时间跳跃");
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
        when(llmApi.chat(eq(LlmTaskType.USER_FACING), any()))
                .thenReturn("ack");

        service.chat(xiaowanDto(), 1L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmApi).chat(eq(LlmTaskType.USER_FACING), captor.capture());
        List<ChatMessage> sent = captor.getValue();
        // 1 system + 2 history
        assertThat(sent).hasSize(3);
        assertThat(sent.get(1).role()).isEqualTo("user");
        assertThat(sent.get(1).content()).isEqualTo("你好");
        assertThat(sent.get(2).role()).isEqualTo("assistant");
        assertThat(sent.get(2).content()).isEqualTo("你好呀");
    }

    @Test
    void chat_shouldUseShortTermWindowSizeFromMemoryConstants() {
        when(messageRepository.findByUserIdOrderByIdDesc(eq(1L), any()))
                .thenReturn(List.of());
        when(llmApi.chat(eq(LlmTaskType.USER_FACING), any()))
                .thenReturn("ok");

        service.chat(xiaowanDto(), 1L);

        ArgumentCaptor<PageRequest> pageCaptor = ArgumentCaptor.forClass(PageRequest.class);
        verify(messageRepository).findByUserIdOrderByIdDesc(eq(1L), pageCaptor.capture());
        // Q3 回归断言：必须严格 == MemoryConstants.SHORT_TERM_WINDOW_SIZE，防止后续有人改回字面量 20 或 32
        assertThat(pageCaptor.getValue().getPageSize())
                .as("短期窗口大小必须从 MemoryConstants.SHORT_TERM_WINDOW_SIZE 取，禁止字面量")
                .isEqualTo(MemoryConstants.SHORT_TERM_WINDOW_SIZE);
        assertThat(pageCaptor.getValue().getPageNumber()).isZero();
    }

    // ------------------------------------------------------------------
    // Q4：AiService 注入 MemoryApi —— 调用 / 降级 / 参数传递
    // ------------------------------------------------------------------

    @Test
    void chat_shouldStillWorkWhenMemoryApiReturnsNull() {
        // MemoryApi 三层皆空时合约约定返回 null，AiService 必须照常工作（PromptBuilder 接受 null memoryContext）
        when(messageRepository.findByUserIdOrderByIdDesc(anyLong(), any()))
                .thenReturn(new ArrayList<>(List.of()));
        when(memoryApi.getRelevantContext(anyLong(), anyLong(), any()))
                .thenReturn(null);
        when(llmApi.chat(eq(LlmTaskType.USER_FACING), any()))
                .thenReturn("ok");

        String reply = service.chat(xiaowanDto(), 42L);
        assertThat(reply).isEqualTo("ok");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmApi).chat(eq(LlmTaskType.USER_FACING), captor.capture());
        List<ChatMessage> sent = captor.getValue();
        // 只有人设 system 消息，没有「她对你的记忆：」段
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).role()).isEqualTo("system");
        assertThat(sent.get(0).content()).doesNotContain(PromptBuilder.MEMORY_PREFIX);
    }

    @Test
    void chat_shouldInjectMemoryContextIntoPromptWhenAvailable() {
        MessageEntity userMsg = new MessageEntity();
        userMsg.setSenderType(SenderType.USER);
        userMsg.setContent("我最近想去北海道");
        when(messageRepository.findByUserIdOrderByIdDesc(eq(42L), any()))
                .thenReturn(new ArrayList<>(List.of(userMsg)));
        when(memoryApi.getRelevantContext(eq(42L), eq(DEFAULT_CHARACTER_ID), eq("我最近想去北海道")))
                .thenReturn(new MemoryContext("她记得你喜欢滑雪"));
        when(llmApi.chat(eq(LlmTaskType.USER_FACING), any()))
                .thenReturn("好呀我陪你");

        service.chat(xiaowanDto(), 42L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmApi).chat(eq(LlmTaskType.USER_FACING), captor.capture());
        List<ChatMessage> sent = captor.getValue();
        // 期望顺序：[0]人设 system / [1]记忆 system / [2]user 消息
        assertThat(sent).hasSize(3);
        assertThat(sent.get(0).role()).isEqualTo("system");
        assertThat(sent.get(0).content()).contains(SYSTEM_PROMPT_MARKER);
        assertThat(sent.get(1).role()).isEqualTo("system");
        assertThat(sent.get(1).content())
                .as("Memory context 必须以固定前缀注入，避免污染人设")
                .startsWith(PromptBuilder.MEMORY_PREFIX)
                .contains("她记得你喜欢滑雪");
        assertThat(sent.get(2).role()).isEqualTo("user");
        assertThat(sent.get(2).content()).isEqualTo("我最近想去北海道");
    }

    @Test
    void chat_shouldPassLatestUserMessageContentAsRagQuery() {
        // 历史：user("早") → ai("早呀") → user("帮我推荐去东京的路线") ←最新的 user 消息
        MessageEntity oldestUser = new MessageEntity();
        oldestUser.setSenderType(SenderType.USER);
        oldestUser.setContent("早");
        MessageEntity aiReply = new MessageEntity();
        aiReply.setSenderType(SenderType.AI);
        aiReply.setContent("早呀");
        MessageEntity latestUser = new MessageEntity();
        latestUser.setSenderType(SenderType.USER);
        latestUser.setContent("帮我推荐去东京的路线");
        // Repository 按 id desc 返回（最新在前），AiService 内部会 reverse 成时间正序
        when(messageRepository.findByUserIdOrderByIdDesc(eq(7L), any()))
                .thenReturn(new ArrayList<>(List.of(latestUser, aiReply, oldestUser)));
        when(memoryApi.getRelevantContext(anyLong(), anyLong(), any())).thenReturn(null);
        when(llmApi.chat(eq(LlmTaskType.USER_FACING), any())).thenReturn("ok");

        AiCharacterDto character = xiaowanDto();  // id = 1L
        service.chat(character, 7L);

        // 必须用「最新的 user 消息内容」作为 RAG query，characterId 用 character.id()
        verify(memoryApi).getRelevantContext(eq(7L), eq(1L), eq("帮我推荐去东京的路线"));
    }

    @Test
    void chat_shouldFallbackToDefaultCharacterIdWhenEntityHasNoId() {
        AiCharacterDto withoutId = new AiCharacterDto(null, "无 id 的角色", null);

        MessageEntity userMsg = new MessageEntity();
        userMsg.setSenderType(SenderType.USER);
        userMsg.setContent("你好");
        when(messageRepository.findByUserIdOrderByIdDesc(eq(9L), any()))
                .thenReturn(new ArrayList<>(List.of(userMsg)));
        when(memoryApi.getRelevantContext(anyLong(), anyLong(), any())).thenReturn(null);
        when(llmApi.chat(eq(LlmTaskType.USER_FACING), any())).thenReturn("ok");

        service.chat(withoutId, 9L);

        // character.id() 为 null 时回落到 defaultCharacterId (=1L)
        verify(memoryApi).getRelevantContext(eq(9L), eq(DEFAULT_CHARACTER_ID), eq("你好"));
    }

    @Test
    void chat_shouldDegradeGracefullyWhenMemoryApiThrows() {
        // MemoryApi 异常不能影响主对话——catch + log + 继续走 PromptBuilder（memoryContext=null）
        MessageEntity userMsg = new MessageEntity();
        userMsg.setSenderType(SenderType.USER);
        userMsg.setContent("hi");
        when(messageRepository.findByUserIdOrderByIdDesc(anyLong(), any()))
                .thenReturn(new ArrayList<>(List.of(userMsg)));
        when(memoryApi.getRelevantContext(anyLong(), anyLong(), any()))
                .thenThrow(new RuntimeException("embedding 服务挂了"));
        when(llmApi.chat(eq(LlmTaskType.USER_FACING), any()))
                .thenReturn("ok");

        String reply = service.chat(xiaowanDto(), 1L);
        assertThat(reply).isEqualTo("ok");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmApi).chat(eq(LlmTaskType.USER_FACING), captor.capture());
        List<ChatMessage> sent = captor.getValue();
        // 没有「她对你的记忆」段（降级），但人设 system + 1 条 user 消息仍存在
        assertThat(sent).hasSize(2);
        assertThat(sent.get(0).role()).isEqualTo("system");
        assertThat(sent.get(0).content()).doesNotContain(PromptBuilder.MEMORY_PREFIX);
        assertThat(sent.get(1).role()).isEqualTo("user");
        assertThat(sent.get(1).content()).isEqualTo("hi");
    }

    @Test
    void chat_shouldUseEmptyStringAsRagQueryWhenNoUserMessageExists() {
        // 边界场景：用户首次对话还没发消息（recent 全是 AI 系统欢迎语之类）
        MessageEntity aiOnly = new MessageEntity();
        aiOnly.setSenderType(SenderType.AI);
        aiOnly.setContent("欢迎");
        when(messageRepository.findByUserIdOrderByIdDesc(anyLong(), any()))
                .thenReturn(new ArrayList<>(List.of(aiOnly)));
        when(memoryApi.getRelevantContext(anyLong(), anyLong(), any())).thenReturn(null);
        when(llmApi.chat(eq(LlmTaskType.USER_FACING), any())).thenReturn("ok");

        service.chat(xiaowanDto(), 1L);

        // 没有 user 消息时，RAG query 用空串占位（不传 null，避免 -core 端 NPE）
        verify(memoryApi).getRelevantContext(eq(1L), eq(DEFAULT_CHARACTER_ID), eq(""));
    }

    // ------------------------------------------------------------------
    // I3：AiService 调 CharacterApi.getStagePromptSegment 注入 stage prompt
    // ------------------------------------------------------------------

    @Test
    void chat_shouldInjectStagePromptSegmentFromCharacterApi() {
        when(messageRepository.findByUserIdOrderByIdDesc(eq(42L), any()))
                .thenReturn(new ArrayList<>(List.of()));
        when(memoryApi.getRelevantContext(anyLong(), anyLong(), any()))
                .thenReturn(null);
        when(characterApi.getStagePromptSegment(42L, 1L))
                .thenReturn("当前关系阶段：朋友。称呼：你。");
        when(llmApi.chat(eq(LlmTaskType.USER_FACING), any()))
                .thenReturn("ok");

        service.chat(xiaowanDto(), 42L);

        // 验证 PromptBuilder 接到了 characterApi 返回的 stage prompt
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmApi).chat(eq(LlmTaskType.USER_FACING), captor.capture());
        List<ChatMessage> sent = captor.getValue();
        // system[0] = 人设；system[1] = stage prompt segment
        assertThat(sent).hasSize(2);
        assertThat(sent.get(1).role()).isEqualTo("system");
        assertThat(sent.get(1).content()).isEqualTo("当前关系阶段：朋友。称呼：你。");
    }

    @Test
    void chat_shouldSkipStagePromptWhenCharacterApiReturnsBlank() {
        when(messageRepository.findByUserIdOrderByIdDesc(anyLong(), any()))
                .thenReturn(new ArrayList<>(List.of()));
        when(memoryApi.getRelevantContext(anyLong(), anyLong(), any()))
                .thenReturn(null);
        when(characterApi.getStagePromptSegment(anyLong(), anyLong()))
                .thenReturn("");
        when(llmApi.chat(eq(LlmTaskType.USER_FACING), any()))
                .thenReturn("ok");

        service.chat(xiaowanDto(), 1L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmApi).chat(eq(LlmTaskType.USER_FACING), captor.capture());
        List<ChatMessage> sent = captor.getValue();
        // stage prompt 为空时 PromptBuilder 跳过，只有人设 system 消息
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).role()).isEqualTo("system");
    }

    /**
     * 测试用的 xiaowan DTO 构造器（id = 1L，与 character-core 内 AiCharacterTestFixtures.xiaowan() 对齐）。
     * <p>AiCharacterDto 是简单 record，按 java-backend rules §5.2「简单 DTO 不强制 Object Mother」直接在本测试内构造。
     */
    private static AiCharacterDto xiaowanDto() {
        return new AiCharacterDto(1L, "小婉", null);
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
