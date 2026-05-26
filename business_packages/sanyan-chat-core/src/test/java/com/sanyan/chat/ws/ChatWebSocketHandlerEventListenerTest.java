package com.sanyan.chat.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanyan.character.event.IntimacyChangedEvent;
import com.sanyan.character.event.StageEntryStoryEvent;
import com.sanyan.character.event.StageTransitionEvent;
import com.sanyan.chat.internal.DeliveryService;
import com.sanyan.chat.internal.MessageService;
import com.sanyan.common.ws.LastActiveTracker;
import com.sanyan.common.ws.SessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatWebSocketHandlerEventListenerTest {

    @Mock SessionManager sessionManager;
    @Mock MessageService messageService;
    @Mock LastActiveTracker lastActiveTracker;
    @Mock DeliveryService deliveryService;
    @Mock WebSocketSession session;

    private ChatWebSocketHandler handler;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        handler = new ChatWebSocketHandler(sessionManager, objectMapper, messageService, lastActiveTracker, deliveryService);
    }

    // ----- IntimacyChangedEvent -----

    @Test
    void onIntimacyChanged_should_push_intimacy_update_frame_to_user() throws Exception {
        when(session.isOpen()).thenReturn(true);
        when(sessionManager.getSession(1L)).thenReturn(Optional.of(session));

        handler.onIntimacyChanged(new IntimacyChangedEvent(1L, 2L, 10, 15, 5, "MESSAGE_SENT"));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());
        String payload = captor.getValue().getPayload();
        assertThat(payload).contains("\"type\":\"intimacy_update\"");
        assertThat(payload).contains("\"score\":15");
        assertThat(payload).contains("\"delta\":5");
        assertThat(payload).contains("\"reason\":\"MESSAGE_SENT\"");
    }

    @Test
    void onIntimacyChanged_should_skip_when_no_session() {
        when(sessionManager.getSession(1L)).thenReturn(Optional.empty());

        handler.onIntimacyChanged(new IntimacyChangedEvent(1L, 2L, 10, 15, 5, "MESSAGE_SENT"));

        verifyNoInteractions(session);
    }

    // ----- StageTransitionEvent -----

    @Test
    void onStageTransition_should_push_stage_transition_frame_to_user() throws Exception {
        when(session.isOpen()).thenReturn(true);
        when(sessionManager.getSession(1L)).thenReturn(Optional.of(session));

        handler.onStageTransition(new StageTransitionEvent(1L, 2L, 1, 2));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());
        String payload = captor.getValue().getPayload();
        assertThat(payload).contains("\"type\":\"stage_transition\"");
        assertThat(payload).contains("\"from\":1");
        assertThat(payload).contains("\"to\":2");
    }

    @Test
    void onStageTransition_should_skip_when_no_session() {
        when(sessionManager.getSession(99L)).thenReturn(Optional.empty());

        handler.onStageTransition(new StageTransitionEvent(99L, 2L, 0, 1));

        verifyNoInteractions(session);
    }

    // ----- StageEntryStoryEvent -----

    @Test
    void onStageEntryStory_should_push_stage_story_frame_to_user() throws Exception {
        when(session.isOpen()).thenReturn(true);
        when(sessionManager.getSession(1L)).thenReturn(Optional.of(session));

        handler.onStageEntryStory(new StageEntryStoryEvent(1L, 2L, 3, "她第一次叫你「宝贝」……"));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());
        String payload = captor.getValue().getPayload();
        assertThat(payload).contains("\"type\":\"stage_story\"");
        assertThat(payload).contains("\"stage\":3");
        assertThat(payload).contains("\"story_message\"");
        assertThat(payload).contains("宝贝");
    }

    @Test
    void onStageEntryStory_should_skip_when_no_session() {
        when(sessionManager.getSession(5L)).thenReturn(Optional.empty());

        handler.onStageEntryStory(new StageEntryStoryEvent(5L, 2L, 1, "某个剧情文案"));

        verifyNoInteractions(session);
    }
}
