package com.sanyan.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanyan.dto.data.ConversationData;
import com.sanyan.dto.data.MessageData;
import com.sanyan.dto.ws.*;
import com.sanyan.entity.Message;
import com.sanyan.service.MessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.*;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketHandler extends TextWebSocketHandler {

    private final SessionManager sessionManager;
    private final ObjectMapper objectMapper;
    private final MessageService messageService;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long userId = (Long) session.getAttributes().get("userId");
        sessionManager.register(userId, session);
        log.info("用户 {} WebSocket 已连接", userId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Long userId = (Long) session.getAttributes().get("userId");
        WsMessage wsMsg = objectMapper.readValue(message.getPayload(), WsMessage.class);

        switch (wsMsg.getType()) {
            case "ping" -> sendToSession(session, "{\"type\":\"pong\"}");
            case "send_message" -> handleSendMessage(userId, wsMsg, session);
            case "sync" -> handleSync(userId, wsMsg, session);
            default -> log.warn("未知消息类型: {}", wsMsg.getType());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long userId = (Long) session.getAttributes().get("userId");
        if (userId != null) {
            sessionManager.remove(userId);
            log.info("用户 {} WebSocket 已断开", userId);
        }
    }

    private void handleSendMessage(Long userId, WsMessage wsMsg, WebSocketSession session) {
        // 1. Send ACK
        WsAck ack = new WsAck(wsMsg.getClientMsgId());
        sendObject(session, ack);

        // 2. Send typing status
        WsTyping typing = new WsTyping(wsMsg.getConversationId());
        sendObject(session, typing);

        // 3. Async: call AI, calculate delay, send new_message
        CompletableFuture.runAsync(() -> {
            try {
                Message aiMsg = messageService.handleUserMessage(
                        userId, wsMsg.getConversationId(), wsMsg.getContentType(), wsMsg.getContent());

                long delay = messageService.calculateTypingDelay(aiMsg.getContent());
                Thread.sleep(delay);

                MessageData data = messageService.toData(aiMsg);
                WsNewMessage newMsg = new WsNewMessage(data);
                sendObject(session, newMsg);

            } catch (Exception e) {
                log.error("处理用户消息失败, userId={}", userId, e);
            }
        });
    }

    private void handleSync(Long userId, WsMessage wsMsg, WebSocketSession session) {
        List<ConversationData> conversations = messageService.getUserConversations(userId);

        Map<Long, List<MessageData>> conversationMessages = new LinkedHashMap<>();
        for (ConversationData conv : conversations) {
            List<Message> messages = messageService.syncMessages(
                    conv.getId(), wsMsg.getLastMsgId(), 50);
            if (!messages.isEmpty()) {
                conversationMessages.put(conv.getId(),
                        messages.stream().map(messageService::toData).toList());
            }
        }

        WsSyncResult syncResult = new WsSyncResult(conversationMessages);
        sendObject(session, syncResult);
    }

    public void sendToSession(WebSocketSession session, String payload) {
        try {
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(payload));
                }
            }
        } catch (Exception e) {
            log.error("WebSocket 发送失败", e);
        }
    }

    private void sendObject(WebSocketSession session, Object obj) {
        try {
            String payload = objectMapper.writeValueAsString(obj);
            sendToSession(session, payload);
        } catch (Exception e) {
            log.error("WebSocket 序列化发送失败", e);
        }
    }
}
