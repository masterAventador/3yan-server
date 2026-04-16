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
    void isSendableContent_shouldReturnFalseForBlank() {
        assertThat(ProactiveService.isSendableContent(null)).isFalse();
        assertThat(ProactiveService.isSendableContent("")).isFalse();
        assertThat(ProactiveService.isSendableContent("   ")).isFalse();
        assertThat(ProactiveService.isSendableContent("\n")).isFalse();
    }

    @Test
    void isSendableContent_shouldReturnTrueForActualContent() {
        assertThat(ProactiveService.isSendableContent("嗨，最近怎么样？")).isTrue();
        assertThat(ProactiveService.isSendableContent("a")).isTrue();
    }
}
