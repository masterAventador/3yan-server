package com.sanyan.memory.event;

import com.sanyan.chat.ChatApi;
import com.sanyan.chat.SenderType;
import com.sanyan.chat.dto.MessageDto;
import com.sanyan.chat.event.MessagePersistedEvent;
import com.sanyan.memory.internal.item.MemoryItemExtractService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemoryItemExtractListenerTest {

    @Mock ChatApi chatApi;
    @Mock MemoryItemExtractService extractService;
    @InjectMocks MemoryItemExtractListener listener;

    @Test
    void should_skip_when_role_is_ai() {
        listener.onMessagePersisted(new MessagePersistedEvent(10L, 7L, 1L, SenderType.AI));

        verifyNoInteractions(extractService);
        verify(chatApi, never()).listRecentByUser(anyLong(), anyInt());
    }

    @Test
    void should_extract_latest_user_message_for_user_role() {
        // MessageDto 真实签名 (id, userId, senderType, content, createdAt)，senderType 是 String
        when(chatApi.listRecentByUser(7L, 1))
                .thenReturn(List.of(new MessageDto(10L, 7L, SenderType.USER, "周三我有个面试", LocalDateTime.now())));

        listener.onMessagePersisted(new MessagePersistedEvent(10L, 7L, 1L, SenderType.USER));

        verify(extractService).extract(7L, 1L, "周三我有个面试", 10L);
    }

    @Test
    void should_skip_when_no_recent_message_found() {
        when(chatApi.listRecentByUser(7L, 1)).thenReturn(List.of());

        listener.onMessagePersisted(new MessagePersistedEvent(10L, 7L, 1L, SenderType.USER));

        verify(extractService, never()).extract(anyLong(), anyLong(), any(), anyLong());
    }

    @Test
    void should_swallow_exception_from_extract_service() {
        when(chatApi.listRecentByUser(7L, 1))
                .thenReturn(List.of(new MessageDto(10L, 7L, SenderType.USER, "面试好紧张", LocalDateTime.now())));
        org.mockito.Mockito.doThrow(new RuntimeException("LLM 挂了"))
                .when(extractService).extract(eq(7L), eq(1L), any(), eq(10L));

        // 不抛异常（吞掉 + log.error），后台任务失败不影响主对话
        listener.onMessagePersisted(new MessagePersistedEvent(10L, 7L, 1L, SenderType.USER));

        verify(extractService).extract(7L, 1L, "面试好紧张", 10L);
    }
}
