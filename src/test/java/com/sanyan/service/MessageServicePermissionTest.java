package com.sanyan.service;

import com.sanyan.entity.Conversation;
import com.sanyan.repository.AiCharacterRepository;
import com.sanyan.repository.ConversationRepository;
import com.sanyan.repository.MessageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessageServicePermissionTest {

    @Mock ConversationRepository conversationRepository;
    @Mock MessageRepository messageRepository;
    @Mock AiCharacterRepository characterRepository;
    @Mock AiService aiService;
    @Mock TtsService ttsService;
    @Mock CosService cosService;
    @Mock AsrService asrService;
    @Mock StringRedisTemplate redisTemplate;
    @InjectMocks MessageService service;

    @Test
    void getHistoryMessages_whenUserNotOwner_throwsIllegalArgument() {
        Conversation conv = new Conversation();
        conv.setId(10L);
        conv.setUserId(1L);
        when(conversationRepository.findById(10L)).thenReturn(Optional.of(conv));

        assertThatThrownBy(() -> service.getHistoryMessages(2L, 10L, null, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无权访问");
    }

    @Test
    void getHistoryMessages_whenConversationMissing_throwsNotFound() {
        when(conversationRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getHistoryMessages(1L, 99L, null, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("会话不存在");
    }

    @Test
    void syncMessages_whenUserNotOwner_throwsIllegalArgument() {
        Conversation conv = new Conversation();
        conv.setId(10L);
        conv.setUserId(1L);
        when(conversationRepository.findById(10L)).thenReturn(Optional.of(conv));

        assertThatThrownBy(() -> service.syncMessages(2L, 10L, null, 50))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无权访问");
    }
}
