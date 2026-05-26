package com.sanyan.chat.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanyan.chat.internal.DeliveryService;
import com.sanyan.chat.internal.MessageService;
import com.sanyan.common.ws.LastActiveTracker;
import com.sanyan.common.ws.SessionManager;
import com.sanyan.common.ws.WsEventType;
import com.sanyan.common.ws.WsMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ChatWebSocketHandlerAckTest {

    @Mock SessionManager sessionManager;
    @Mock MessageService messageService;
    @Mock LastActiveTracker lastActiveTracker;
    @Mock DeliveryService deliveryService;

    @Test
    void handleAck_should_delegate_ackMsgId_to_deliveryService() {
        // 构造器字段声明顺序：sessionManager, objectMapper, messageService, lastActiveTracker, deliveryService
        ChatWebSocketHandler handler = new ChatWebSocketHandler(
                sessionManager, new ObjectMapper(), messageService, lastActiveTracker, deliveryService);

        WsMessage ack = new WsMessage();
        ack.setType(WsEventType.ACK);
        ack.setAckMsgId(777L);

        handler.handleAck(ack);

        verify(deliveryService).confirmAck(777L);
    }
}
