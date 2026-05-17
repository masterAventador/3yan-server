package com.sanyan.chat.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanyan.chat.internal.MessageEntity;
import com.sanyan.chat.internal.MessageService;
import com.sanyan.chat.web.MessageData;
import com.sanyan.common.util.TypingDelayCalculator;
import com.sanyan.common.ws.SessionManager;
import com.sanyan.common.ws.WsEventType;
import com.sanyan.common.ws.WsMessage;
import com.sanyan.common.ws.WsTyping;
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
public class ChatWebSocketHandler extends TextWebSocketHandler {

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

        // 1. 先落 user 消息，拿到 server 端真实 id
        MessageEntity userMsg;
        try {
            userMsg = messageService.saveUserMessage(userId, wsMsg.getContent());
        } catch (Exception e) {
            log.error("保存用户消息失败, userId={}", userId, e);
            sendObject(session, new WsError(wsMsg.getClientMsgId(), WsErrorMessage.MESSAGE_PROCESSING_FAILED));
            return;
        }

        // 2. ACK 带 serverMsgId
        sendObject(session, new WsAck(wsMsg.getClientMsgId(), userMsg.getId()));

        // 3. typing
        sendObject(session, new WsTyping());

        // 4. async: AI 调用 + 拆分多条 + 按节奏推送（typing → delay → message → gap → typing → ...）
        //    成功 / 失败两条路径都要发 turn_complete，让客户端不必靠"静默 Xs 没新事件"猜结束
        CompletableFuture.runAsync(() -> {
            int pushedCount = 0;
            try {
                List<MessageEntity> aiMessages = messageService.handleAiReply(userId);
                for (int i = 0; i < aiMessages.size(); i++) {
                    MessageEntity aiMsg = aiMessages.get(i);
                    if (i > 0) {
                        long gap = TypingDelayCalculator.calculateInterMessageGap();
                        Thread.sleep(gap);
                        sendObject(session, new WsTyping());
                    }
                    long delay = TypingDelayCalculator.calculateTypingDelay(aiMsg.getContent());
                    Thread.sleep(delay);
                    sendObject(session, new WsNewMessage(messageService.toData(aiMsg)));
                    pushedCount++;
                    log.info("AI 回复气泡推送: userId={}, msgId={}, idx={}/{}",
                            userId, aiMsg.getId(), i + 1, aiMessages.size());
                }

            } catch (Exception e) {
                log.error("处理 AI 回复失败, userId={}", userId, e);
                sendObject(session, new WsError(wsMsg.getClientMsgId(),
                        WsErrorMessage.MESSAGE_PROCESSING_FAILED));
            } finally {
                sendObject(session, new WsTurnComplete(wsMsg.getClientMsgId(), pushedCount));
            }
        });
    }

    private void handleSync(Long userId, WsMessage wsMsg, WebSocketSession session) {
        log.info("消息同步请求: userId={}, lastMsgId={}", userId, wsMsg.getLastMsgId());
        List<MessageEntity> messages = messageService.syncMessages(userId, wsMsg.getLastMsgId(), 50);
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
