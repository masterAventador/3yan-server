package com.sanyan.service;

import com.sanyan.entity.MemorySummary;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AiServiceTest {

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
        var messageRepo = mock(com.sanyan.repository.MessageRepository.class);
        var memoryProfileRepo = mock(com.sanyan.repository.MemoryProfileRepository.class);
        var memorySummaryRepo = mock(com.sanyan.repository.MemorySummaryRepository.class);
        var objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var restTemplate = mock(org.springframework.web.client.RestTemplate.class);

        AiService aiService = new AiService(messageRepo, memoryProfileRepo, memorySummaryRepo, objectMapper, restTemplate);

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
        var messageRepo = mock(com.sanyan.repository.MessageRepository.class);
        var memoryProfileRepo = mock(com.sanyan.repository.MemoryProfileRepository.class);
        var memorySummaryRepo = mock(com.sanyan.repository.MemorySummaryRepository.class);
        var objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var restTemplate = mock(org.springframework.web.client.RestTemplate.class);

        AiService aiService = new AiService(messageRepo, memoryProfileRepo, memorySummaryRepo, objectMapper, restTemplate);

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
    void chat_shouldReturnFallbackString_whenDoubaoFallsBack() throws Exception {
        // 验证 chat 链路（用户主动发消息）保持原行为：失败时返回兜底文案
        var messageRepo = mock(com.sanyan.repository.MessageRepository.class);
        var memoryProfileRepo = mock(com.sanyan.repository.MemoryProfileRepository.class);
        var memorySummaryRepo = mock(com.sanyan.repository.MemorySummaryRepository.class);
        var objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var restTemplate = mock(org.springframework.web.client.RestTemplate.class);

        AiService aiService = new AiService(messageRepo, memoryProfileRepo, memorySummaryRepo, objectMapper, restTemplate);

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
