package com.sanyan.service;

import com.sanyan.dto.ws.MessageContentType;
import com.sanyan.entity.AiCharacter;
import com.sanyan.entity.Conversation;
import com.sanyan.entity.Message;
import com.sanyan.repository.AiCharacterRepository;
import com.sanyan.repository.ConversationRepository;
import com.sanyan.repository.MessageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MessageServiceFallbackReasonTest {

    @Mock ConversationRepository conversationRepository;
    @Mock MessageRepository messageRepository;
    @Mock AiCharacterRepository characterRepository;
    @Mock AiService aiService;
    @Mock TtsService ttsService;
    @Mock CosService cosService;
    @Mock AsrService asrService;
    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOps;
    @Mock ListOperations<String, String> listOps;
    @InjectMocks MessageService service;

    @Test
    void asrFailed_marksMessageAsFallback() {
        setupCommon();
        when(asrService.isEnabled()).thenReturn(true);
        when(asrService.transcribe(anyString())).thenReturn(null);
        when(aiService.chatVoiceAck(any(), anyLong())).thenReturn("嗯嗯");
        when(ttsService.isEnabled()).thenReturn(false);

        service.handleUserMessage(1L, 10L, MessageContentType.VOICE, "", "http://v.mp3", 3);

        Message ai = captureAiMessage();
        assertThat(ai.getFallbackReason()).isEqualTo("asr_failed");
    }

    @Test
    void ttsFailed_marksMessageAsFallback() {
        setupCommon();
        when(ttsService.isEnabled()).thenReturn(true);
        when(ttsService.synthesize(anyString(), any())).thenReturn(null);
        when(aiService.chat(any(), anyLong())).thenReturn("好的");

        service.handleUserMessage(1L, 10L, MessageContentType.TEXT, "hi", null, null);

        Message ai = captureAiMessage();
        assertThat(ai.getFallbackReason()).isEqualTo("tts_failed");
    }

    @Test
    void normalPath_hasNoFallback() {
        setupCommon();
        when(ttsService.isEnabled()).thenReturn(false);
        when(aiService.chat(any(), anyLong())).thenReturn("嗨");

        service.handleUserMessage(1L, 10L, MessageContentType.TEXT, "hi", null, null);

        Message ai = captureAiMessage();
        assertThat(ai.getFallbackReason()).isNull();
    }

    private void setupCommon() {
        Conversation conv = new Conversation();
        conv.setId(10L);
        conv.setUserId(1L);
        conv.setCharacterId(100L);
        conv.setUnreadCount(0);
        when(conversationRepository.findById(10L)).thenReturn(Optional.of(conv));
        when(characterRepository.findById(100L)).thenReturn(Optional.of(new AiCharacter()));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.opsForList()).thenReturn(listOps);
    }

    private Message captureAiMessage() {
        ArgumentCaptor<Message> cap = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository, atLeast(2)).save(cap.capture());
        return cap.getAllValues().stream()
                .filter(m -> "ai".equals(m.getSenderType()))
                .findFirst().orElseThrow();
    }
}
