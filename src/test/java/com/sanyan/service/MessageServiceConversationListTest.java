package com.sanyan.service;

import com.sanyan.entity.AiCharacter;
import com.sanyan.entity.Conversation;
import com.sanyan.entity.Message;
import com.sanyan.repository.AiCharacterRepository;
import com.sanyan.repository.ConversationRepository;
import com.sanyan.repository.MessageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MessageServiceConversationListTest {

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
    void getUserConversations_usesBatchQueries_notNPlusOne() {
        Conversation c1 = conv(1L, 100L);
        Conversation c2 = conv(2L, 100L); // 同角色
        Conversation c3 = conv(3L, 200L);
        when(conversationRepository.findByUserIdOrderByLastMessageAtDesc(7L))
                .thenReturn(List.of(c1, c2, c3));
        when(characterRepository.findAllById(any()))
                .thenReturn(List.of(character(100L, "A"), character(200L, "B")));
        when(messageRepository.findLastMessagePerConversation(any()))
                .thenReturn(List.of(
                        lastMsg(1L, "hi1"),
                        lastMsg(2L, "hi2"),
                        lastMsg(3L, "hi3")
                ));

        var result = service.getUserConversations(7L);

        assertThat(result).hasSize(3);
        assertThat(result.get(0).getCharacterName()).isEqualTo("A");
        assertThat(result.get(0).getLastMessage()).isEqualTo("hi1");
        assertThat(result.get(2).getCharacterName()).isEqualTo("B");
        verify(characterRepository, times(1)).findAllById(any());
        verify(characterRepository, never()).findById(anyLong());
        verify(messageRepository, times(1)).findLastMessagePerConversation(any());
        verify(messageRepository, never()).findByConversationIdOrderByIdDesc(anyLong(), any(Pageable.class));
    }

    @Test
    void getUserConversations_emptyList_returnsEmpty() {
        when(conversationRepository.findByUserIdOrderByLastMessageAtDesc(7L))
                .thenReturn(List.of());
        var result = service.getUserConversations(7L);
        assertThat(result).isEmpty();
        verifyNoInteractions(characterRepository);
    }

    private Conversation conv(long id, long charId) {
        Conversation c = new Conversation();
        c.setId(id);
        c.setUserId(7L);
        c.setCharacterId(charId);
        c.setUnreadCount(0);
        return c;
    }

    private AiCharacter character(long id, String name) {
        AiCharacter a = new AiCharacter();
        a.setId(id);
        a.setName(name);
        return a;
    }

    private Message lastMsg(long convId, String content) {
        Message m = new Message();
        m.setConversationId(convId);
        m.setContent(content);
        return m;
    }
}
