package com.sanyan.service;

import com.sanyan.entity.MemorySummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AiServiceTest {

    private com.sanyan.repository.MessageRepository messageRepo;
    private com.sanyan.repository.MemoryProfileRepository memoryProfileRepo;
    private com.sanyan.repository.MemorySummaryRepository memorySummaryRepo;
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private org.springframework.web.client.RestTemplate restTemplate;
    private AiService aiService;

    @BeforeEach
    void setUp() {
        messageRepo = mock(com.sanyan.repository.MessageRepository.class);
        memoryProfileRepo = mock(com.sanyan.repository.MemoryProfileRepository.class);
        memorySummaryRepo = mock(com.sanyan.repository.MemorySummaryRepository.class);
        objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        restTemplate = mock(org.springframework.web.client.RestTemplate.class);
        aiService = new AiService(messageRepo, memoryProfileRepo, memorySummaryRepo, objectMapper, restTemplate);
    }

    @Test
    void shouldAssemblePromptWithTimeAndProfile() {
        AiService aiService = new AiService(null, null, null, null, null);
        String systemPrompt = "你是小晚";
        String profile = "用户叫小明，是程序员";
        String time = "2026年4月7日 周一 14:30";

        String assembled = aiService.assembleSystemPrompt(systemPrompt, time, profile);

        assertThat(assembled).contains("你是小晚");
        assertThat(assembled).contains("2026年4月7日");
        assertThat(assembled).contains("小明");
    }

    @Test
    void buildProactiveMessages_shouldContainSystemAndUserTurnOnly_noHistory() {
        String characterPrompt = "你是小婉，一个温柔的女生";
        String time = "2026年4月16日 周四 04:53";
        String profile = "用户叫小明，是程序员，最近在装环境";
        List<MemorySummary> summaries = List.of();
        String triggerHint = "用户已经有一段时间没有说话了，主动关心一下";

        List<Map<String, String>> messages = AiService.buildProactiveMessages(
            characterPrompt, time, profile, summaries, triggerHint);

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).get("role")).isEqualTo("system");
        assertThat(messages.get(0).get("content")).contains("你是小婉");
        assertThat(messages.get(0).get("content")).contains("2026年4月16日");
        assertThat(messages.get(0).get("content")).contains("小明");
        assertThat(messages.get(1).get("role")).isEqualTo("user");
        assertThat(messages.get(1).get("content")).contains("系统指令");
        assertThat(messages.get(1).get("content")).contains("用户已经有一段时间没有说话了");
    }

    @Test
    void chatProactive_shouldNotLoadRecentMessages() throws Exception {
        when(memoryProfileRepo.findByConversationId(1L)).thenReturn(java.util.Optional.empty());
        when(memorySummaryRepo.findByConversationIdOrderByCreatedAtDesc(eq(1L), any())).thenReturn(List.of());

        String fakeResp = "{\"choices\":[{\"message\":{\"content\":\"嗨，最近怎么样？[emotion:happy:2]\"}}]}";
        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenReturn(new org.springframework.http.ResponseEntity<>(fakeResp, org.springframework.http.HttpStatus.OK));

        com.sanyan.entity.AiCharacter character = new com.sanyan.entity.AiCharacter();
        character.setSystemPrompt("你是小婉");
        aiService.chatProactive(character, 1L, "主动打招呼");

        verify(messageRepo, never()).findByConversationIdOrderByIdDesc(anyLong(), any());
    }

    @Test
    void chatProactive_shouldReturnNull_whenDoubaoFallsBack() throws Exception {
        when(memoryProfileRepo.findByConversationId(1L)).thenReturn(java.util.Optional.empty());
        when(memorySummaryRepo.findByConversationIdOrderByCreatedAtDesc(eq(1L), any())).thenReturn(List.of());

        // 模拟豆包接口挂掉 → callDoubaoRaw 走 catch 返回兜底
        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenThrow(new org.springframework.web.client.RestClientException("simulated 5xx"));

        com.sanyan.entity.AiCharacter character = new com.sanyan.entity.AiCharacter();
        character.setSystemPrompt("你是小婉");
        String result = aiService.chatProactive(character, 1L, "主动打招呼");

        assertThat(result).isNull();
    }

    @Test
    void buildChatMessages_appendsTtsStyleToAssistantContent() {
        com.sanyan.entity.Message aiMsg = new com.sanyan.entity.Message();
        aiMsg.setSenderType("ai");
        aiMsg.setContent("嗯……你猜？");
        aiMsg.setTtsStyle("有点调皮的感觉，声音上扬");

        com.sanyan.entity.Message userMsg = new com.sanyan.entity.Message();
        userMsg.setSenderType("user");
        userMsg.setContent("想我了没");

        List<Map<String, String>> chat = AiService.buildChatMessages(
                "你是小婉", List.of(), List.of(aiMsg, userMsg));

        // system + 2 history
        assertThat(chat).hasSize(3);
        assertThat(chat.get(0).get("role")).isEqualTo("system");

        // AI 消息应该把 tts_style 拼回去，让下一轮 AI 看到自己的语气
        assertThat(chat.get(1).get("role")).isEqualTo("assistant");
        assertThat(chat.get(1).get("content"))
                .isEqualTo("嗯……你猜？[tts_style:有点调皮的感觉，声音上扬]");

        // 用户消息不应该被加任何后缀
        assertThat(chat.get(2).get("role")).isEqualTo("user");
        assertThat(chat.get(2).get("content")).isEqualTo("想我了没");
    }

    @Test
    void buildChatMessages_aiWithoutTtsStyle_contentStaysClean() {
        // 历史 AI 消息（TTS 失败降级为文字、或旧数据）没有 ttsStyle，不该拼空标签
        com.sanyan.entity.Message aiMsg = new com.sanyan.entity.Message();
        aiMsg.setSenderType("ai");
        aiMsg.setContent("这是没有 style 的回复");
        aiMsg.setTtsStyle(null);

        List<Map<String, String>> chat = AiService.buildChatMessages(
                "sys", List.of(), List.of(aiMsg));

        assertThat(chat.get(1).get("content")).isEqualTo("这是没有 style 的回复");
        assertThat(chat.get(1).get("content")).doesNotContain("[tts_style");
    }

    @Test
    void chat_shouldReturnFallbackString_whenDoubaoFallsBack() throws Exception {
        // 验证 chat 链路（用户主动发消息）保持原行为：失败时返回兜底文案
        when(memoryProfileRepo.findByConversationId(1L)).thenReturn(java.util.Optional.empty());
        when(memorySummaryRepo.findByConversationIdOrderByCreatedAtDesc(eq(1L), any())).thenReturn(List.of());
        when(messageRepo.findByConversationIdOrderByIdDesc(eq(1L), any())).thenReturn(List.of());
        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenThrow(new org.springframework.web.client.RestClientException("simulated 5xx"));

        com.sanyan.entity.AiCharacter character = new com.sanyan.entity.AiCharacter();
        character.setSystemPrompt("你是小婉");
        String result = aiService.chat(character, 1L);

        assertThat(result).isEqualTo(AiService.AI_FALLBACK_MESSAGE);
    }
}
