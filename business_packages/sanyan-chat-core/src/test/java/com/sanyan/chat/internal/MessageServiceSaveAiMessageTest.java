package com.sanyan.chat.internal;

import com.sanyan.chat.SenderType;
import com.sanyan.chat.event.MessagePersistedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessageServiceSaveAiMessageTest {

    @Mock MessageRepository messageRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    // 其余构造器依赖（characterApi / aiService）本测试不触达，给 @Mock 占位避免 NPE
    @Mock com.sanyan.character.CharacterApi characterApi;
    @Mock AiService aiService;
    @InjectMocks MessageService messageService;

    @Test
    void saveAiMessage_should_persist_single_ai_message_and_publish_event() {
        when(messageRepository.save(any(MessageEntity.class))).thenAnswer(inv -> {
            MessageEntity e = inv.getArgument(0);
            e.setId(777L);   // 模拟 DB 生成 id
            return e;
        });

        MessageEntity saved = messageService.saveAiMessage(42L, "早上好呀，今天也要加油哦");

        assertThat(saved.getId()).isEqualTo(777L);
        assertThat(saved.getUserId()).isEqualTo(42L);
        assertThat(saved.getSenderType()).isEqualTo(SenderType.AI);
        assertThat(saved.getContent()).isEqualTo("早上好呀，今天也要加油哦");

        ArgumentCaptor<MessagePersistedEvent> evt = ArgumentCaptor.forClass(MessagePersistedEvent.class);
        verify(eventPublisher).publishEvent(evt.capture());
        assertThat(evt.getValue().messageId()).isEqualTo(777L);
        assertThat(evt.getValue().userId()).isEqualTo(42L);
        assertThat(evt.getValue().role()).isEqualTo(SenderType.AI);
    }

    @Test
    void saveAiMessage_should_default_blank_content_to_empty_string() {
        when(messageRepository.save(any(MessageEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        MessageEntity saved = messageService.saveAiMessage(42L, null);

        assertThat(saved.getContent()).isEmpty();
    }
}
