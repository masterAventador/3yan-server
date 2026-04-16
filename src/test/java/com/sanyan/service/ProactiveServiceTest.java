package com.sanyan.service;

import com.sanyan.entity.Message;
import com.sanyan.repository.MessageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import com.sanyan.repository.ConversationRepository;
import com.sanyan.repository.AiCharacterRepository;
import com.sanyan.websocket.SessionManager;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProactiveServiceTest {

    @Mock private ConversationRepository conversationRepository;
    @Mock private MessageRepository messageRepository;
    @Mock private AiCharacterRepository characterRepository;
    @Mock private AiService aiService;
    @Mock private SessionManager sessionManager;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private PushService pushService;
    @Mock private TtsService ttsService;
    @Mock private CosService cosService;
    @Mock private ObjectMapper objectMapper;

    @InjectMocks private ProactiveService proactiveService;

    @Test
    void shouldCheckRateLimitCorrectly() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("proactive:daily:1")).thenReturn("2");
        assertThat(proactiveService.isRateLimited(1L, 3)).isFalse();

        when(valueOps.get("proactive:daily:1")).thenReturn("3");
        assertThat(proactiveService.isRateLimited(1L, 3)).isTrue();
    }

    @Test
    void shouldCheckLastProactiveUnanswered() {
        Message lastMsg = new Message();
        lastMsg.setSenderType("ai");
        lastMsg.setSource("proactive");
        when(messageRepository.findByConversationIdOrderByIdDesc(eq(100L), any()))
                .thenReturn(List.of(lastMsg));

        assertThat(proactiveService.hasUnansweredProactive(100L)).isTrue();
    }

    @Test
    void shouldNotFlagWhenUserReplied() {
        Message lastMsg = new Message();
        lastMsg.setSenderType("user");
        lastMsg.setSource("reply");
        when(messageRepository.findByConversationIdOrderByIdDesc(eq(100L), any()))
                .thenReturn(List.of(lastMsg));

        assertThat(proactiveService.hasUnansweredProactive(100L)).isFalse();
    }

    @Test
    void hasNonBlankContent_shouldReturnFalseForBlank() {
        assertThat(ProactiveService.hasNonBlankContent(null)).isFalse();
        assertThat(ProactiveService.hasNonBlankContent("")).isFalse();
        assertThat(ProactiveService.hasNonBlankContent("   ")).isFalse();
        assertThat(ProactiveService.hasNonBlankContent("\n")).isFalse();
    }

    @Test
    void hasNonBlankContent_shouldReturnTrueForActualContent() {
        assertThat(ProactiveService.hasNonBlankContent("嗨，最近怎么样？")).isTrue();
        assertThat(ProactiveService.hasNonBlankContent("a")).isTrue();
    }

    @Test
    void sendProactiveMessage_shouldSkipSave_whenAiReplyCleansToBlank() throws Exception {
        // Setup: all guards pass
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("proactive:daily:1")).thenReturn(null); // 没限流
        when(valueOps.get("proactive:last:1")).thenReturn(null);  // 没间隔限制
        // 上一条不是未回复的 proactive
        when(messageRepository.findByConversationIdOrderByIdDesc(eq(100L), any()))
                .thenReturn(java.util.List.of());

        // AI 返回纯标签内容（TextProcessor 清洗后会变成空）
        com.sanyan.entity.AiCharacter character = new com.sanyan.entity.AiCharacter();
        character.setId(99L);
        character.setProactiveConfig(""); // 走默认配置
        when(aiService.chatProactive(any(), eq(100L), anyString()))
                .thenReturn("（在云端轻轻点了点头）[emotion:neutral:1]");

        com.sanyan.entity.Conversation conv = new com.sanyan.entity.Conversation();
        conv.setId(100L);
        conv.setUserId(1L);
        conv.setUnreadCount(0);

        proactiveService.sendProactiveMessage(conv, character, "test trigger");

        // 等待 CompletableFuture.runAsync 异步完成
        java.util.concurrent.ForkJoinPool.commonPool()
                .awaitQuiescence(2, java.util.concurrent.TimeUnit.SECONDS);

        // 关键断言：messageRepository.save 一次都没被调用
        verify(messageRepository, never()).save(any());
    }
}
