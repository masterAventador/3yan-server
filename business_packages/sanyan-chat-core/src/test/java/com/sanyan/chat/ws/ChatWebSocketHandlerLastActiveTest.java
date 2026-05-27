package com.sanyan.chat.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanyan.chat.internal.DeliveryService;
import com.sanyan.chat.internal.MessageEntity;
import com.sanyan.chat.internal.MessageService;
import com.sanyan.common.ws.LastActiveTracker;
import com.sanyan.common.ws.SessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatWebSocketHandlerLastActiveTest {

    @Mock SessionManager sessionManager;
    @Mock MessageService messageService;
    @Mock LastActiveTracker lastActiveTracker;
    @Mock DeliveryService deliveryService;
    @Mock WebSocketSession session;

    ObjectMapper objectMapper = new ObjectMapper();
    ChatWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("userId", 7L);
        lenient().when(session.getAttributes()).thenReturn(attrs);
        lenient().when(session.isOpen()).thenReturn(true);
        // 构造器参数顺序：sessionManager, objectMapper, messageService, lastActiveTracker, deliveryService
        handler = new ChatWebSocketHandler(sessionManager, objectMapper, messageService, lastActiveTracker, deliveryService);
    }

    @Test
    void afterConnectionEstablished_touchesLastActive() {
        handler.afterConnectionEstablished(session);
        verify(lastActiveTracker).touch(7L);
    }

    @Test
    void handleSendMessage_touchesLastActive() throws Exception {
        MessageEntity userMsg = new MessageEntity();
        userMsg.setId(100L);
        when(messageService.saveUserMessage(eq(7L), any())).thenReturn(userMsg);
        lenient().when(messageService.handleAiReply(7L)).thenReturn(List.of());

        handler.handleTextMessage(session,
                new TextMessage("{\"type\":\"send_message\",\"content\":\"hi\",\"clientMsgId\":\"c1\"}"));

        verify(lastActiveTracker).touch(7L);
    }

    @Test
    void handlePing_refreshesOnlineAndRepliesPong() throws Exception {
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"ping\"}"));

        verify(sessionManager).refreshOnline(7L);
        // 仍回 PONG
        org.mockito.ArgumentCaptor<org.springframework.web.socket.TextMessage> cap =
                org.mockito.ArgumentCaptor.forClass(org.springframework.web.socket.TextMessage.class);
        verify(session).sendMessage(cap.capture());
        org.assertj.core.api.Assertions.assertThat(cap.getValue().getPayload()).contains("pong");
    }
}
