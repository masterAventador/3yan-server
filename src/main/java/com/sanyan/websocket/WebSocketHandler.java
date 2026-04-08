package com.sanyan.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanyan.dto.ws.WsMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketHandler extends TextWebSocketHandler {

    private final SessionManager sessionManager;
    private final ObjectMapper objectMapper;

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
        // Task 8 实现完整聊天流程
        log.info("收到用户 {} 消息: {}", userId, wsMsg.getContent());
    }

    private void handleSync(Long userId, WsMessage wsMsg, WebSocketSession session) {
        // Task 8 实现消息同步
        log.info("用户 {} 请求同步, lastMsgId: {}", userId, wsMsg.getLastMsgId());
    }

    public void sendToSession(WebSocketSession session, String payload) {
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(payload));
            }
        } catch (Exception e) {
            log.error("WebSocket 发送失败", e);
        }
    }
}
