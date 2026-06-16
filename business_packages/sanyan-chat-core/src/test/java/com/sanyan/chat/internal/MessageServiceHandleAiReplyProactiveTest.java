package com.sanyan.chat.internal;

import com.sanyan.character.CharacterApi;
import com.sanyan.character.dto.AiCharacterDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对话回复路径（{@link MessageService#handleAiReply}）落库的 AI 消息必须 is_proactive=false，
 * 与主动推送路径（DeliveryService → saveAiMessage(.., true)）区分开。
 */
@ExtendWith(MockitoExtension.class)
class MessageServiceHandleAiReplyProactiveTest {

    @Mock MessageRepository messageRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock CharacterApi characterApi;
    @Mock AiService aiService;
    @InjectMocks MessageService messageService;

    @Test
    void handleAiReply_should_persist_messages_with_proactive_false() {
        when(characterApi.getById(anyLong()))
                .thenReturn(new AiCharacterDto(1L, "小婉", "avatar.png"));
        when(aiService.chat(any(), anyLong())).thenReturn("嗯嗯我在的");
        when(messageRepository.save(any(MessageEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        List<MessageEntity> saved = messageService.handleAiReply(42L);

        assertThat(saved).isNotEmpty();
        ArgumentCaptor<MessageEntity> captor = ArgumentCaptor.forClass(MessageEntity.class);
        verify(messageRepository, atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(m -> assertThat(m.isProactive()).isFalse());
    }
}
