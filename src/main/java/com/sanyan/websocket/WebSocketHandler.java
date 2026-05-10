package com.sanyan.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.List;
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
            case WsEventType.PING -> sendToSession(session, "{\"type\":\"" + WsEventType.PONG + "\"}");
            case WsEventType.SEND_MESSAGE -> handleSendMessage(userId, wsMsg, session);
            case WsEventType.SYNC -> handleSync(userId, wsMsg, session);
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
        String preview = wsMsg.getContent() != null && wsMsg.getContent().length() > 50
                ? wsMsg.getContent().substring(0, 50) + "..." : wsMsg.getContent();
        log.info("收到用户消息: userId={}, clientMsgId={}, content={}", userId, wsMsg.getClientMsgId(), preview);

        // 1. ACK
        sendObject(session, new WsAck(wsMsg.getClientMsgId()));

        // 2. typing
        sendObject(session, new WsTyping());

        // 3. async: AI 调用 + 模拟打字延迟 + 推送
        CompletableFuture.runAsync(() -> {
            try {
                Message aiMsg = messageService.handleUserMessage(userId, wsMsg.getContent());
                long delay = messageService.calculateTypingDelay(aiMsg.getContent());
                log.info("模拟打字延迟: {}ms, userId={}", delay, userId);
                Thread.sleep(delay);

                MessageData data = messageService.toData(aiMsg);
                sendObject(session, new WsNewMessage(data));
                log.info("AI 回复已推送: userId={}, msgId={}", userId, aiMsg.getId());

            } catch (Exception e) {
                log.error("处理用户消息失败, userId={}", userId, e);
                sendObject(session, new WsError(wsMsg.getClientMsgId(),
                        WsErrorMessage.MESSAGE_PROCESSING_FAILED));
            }
        });
    }

    private void handleSync(Long userId, WsMessage wsMsg, WebSocketSession session) {
        log.info("消息同步请求: userId={}, lastMsgId={}", userId, wsMsg.getLastMsgId());
        List<Message> messages = messageService.syncMessages(userId, wsMsg.getLastMsgId(), 50);
        List<MessageData> data = messages.stream().map(messageService::toData).toList();
        sendObject(session, new WsSyncResult(data));
        log.info("消息同步完成: userId={}, 总消息数={}", userId, data.size());
    }

    public void sendToSession(WebSocketSession session, String payload) {
        try {
            synchronized (session) {
                if (session.isOpen()) session.sendMessage(new TextMessage(payload));
            }
        } catch (Exception e) {
            log.error("WebSocket 发送失败", e);
        }
    }

    private void sendObject(WebSocketSession session, Object obj) {
        try {
            sendToSession(session, objectMapper.writeValueAsString(obj));
        } catch (Exception e) {
            log.error("WebSocket 序列化发送失败", e);
        }
    }
}
