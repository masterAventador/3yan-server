package com.sanyan.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanyan.dto.ws.WsEventType;
import com.sanyan.dto.ws.WsMessage;
import com.sanyan.service.MessageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebSocketHandlerErrorTest {

    @Mock SessionManager sessionManager;
    @Mock MessageService messageService;
    @Mock WebSocketSession session;

    @Test
    void handleSendMessage_onAsyncFailure_sendsErrorFrame() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("userId", 1L);
        when(session.getAttributes()).thenReturn(attrs);
        when(session.isOpen()).thenReturn(true);
        when(messageService.handleUserMessage(any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("AI 服务炸了"));

        WebSocketHandler handler = new WebSocketHandler(sessionManager, objectMapper, messageService);

        WsMessage incoming = new WsMessage();
        incoming.setType(WsEventType.SEND_MESSAGE);
        incoming.setConversationId(10L);
        incoming.setClientMsgId("cid-xyz");
        incoming.setContent("hi");
        incoming.setContentType("text");
        handler.handleTextMessage(session, new TextMessage(objectMapper.writeValueAsString(incoming)));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(session, atLeastOnce()).sendMessage(captor.capture());
            assertThat(captor.getAllValues().stream().anyMatch(
                    m -> m.getPayload().contains("\"type\":\"" + WsEventType.ERROR + "\"")
                      && m.getPayload().contains("cid-xyz"))).isTrue();
        });
    }
}
