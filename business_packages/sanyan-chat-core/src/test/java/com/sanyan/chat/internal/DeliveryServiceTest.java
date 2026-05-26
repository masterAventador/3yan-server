package com.sanyan.chat.internal;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanyan.chat.web.MessageData;
import com.sanyan.common.ws.SessionManager;
import com.sanyan.push.PushApi;
import com.sanyan.push.dto.PushPayload;
import com.sanyan.push.dto.PushResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeliveryServiceTest {

    @Mock MessageService messageService;
    @Mock SessionManager sessionManager;
    @Mock PushApi pushApi;
    @Mock WebSocketSession session;

    DeliveryService deliveryService;

    @BeforeEach
    void setUp() {
        deliveryService = new DeliveryService(messageService, sessionManager, pushApi, new ObjectMapper());
        // 缩短 ACK 超时，便于「超时转 push」用例快速触发
        ReflectionTestUtils.setField(deliveryService, "ackTimeoutMs", 150L);
    }

    private MessageData dataOf(long id, String content) {
        MessageData d = new MessageData();
        d.setId(id);
        d.setContent(content);
        return d;
    }

    private MessageEntity entityWithId(long id, String content) {
        MessageEntity e = new MessageEntity();
        e.setId(id);
        e.setContent(content);
        return e;
    }

    @Test
    void deliver_should_save_each_segment_and_return_message_ids() {
        when(messageService.saveAiMessage(eq(1L), any())).thenReturn(entityWithId(10L, "a"), entityWithId(11L, "b"));
        when(sessionManager.getSession(1L)).thenReturn(Optional.empty()); // 离线，专注验证落库 + 返回 id

        List<Long> ids = deliveryService.deliver(1L, 99L, List.of("a", "b"));

        assertThat(ids).containsExactly(10L, 11L);
        verify(messageService, times(2)).saveAiMessage(eq(1L), any());
    }

    @Test
    void deliver_online_and_acked_should_not_push_offline() throws Exception {
        when(messageService.saveAiMessage(eq(1L), any())).thenReturn(entityWithId(10L, "hi"));
        when(messageService.toData(any())).thenReturn(dataOf(10L, "hi"));
        when(sessionManager.getSession(1L)).thenReturn(Optional.of(session));
        when(session.isOpen()).thenReturn(true);

        // 在投递线程里：先发出 WsNewMessage → 注册 future → 等 ACK。用单独线程跑 deliver，主线程 confirmAck。
        Thread t = new Thread(() -> deliveryService.deliver(1L, 99L, List.of("hi")));
        t.start();
        // 等到 future 注册后再 ACK（用 awaitility 轮询 pendingAcks 非空）
        await().atMost(Duration.ofSeconds(2)).until(() ->
                deliveryService.hasPendingAck(10L));
        deliveryService.confirmAck(10L);
        t.join(2000);

        verify(pushApi, never()).pushToUser(anyLong(), any());
    }

    @Test
    void deliver_online_but_ack_timeout_should_push_offline() {
        when(messageService.saveAiMessage(eq(1L), any())).thenReturn(entityWithId(10L, "hi"));
        when(messageService.toData(any())).thenReturn(dataOf(10L, "hi"));
        when(sessionManager.getSession(1L)).thenReturn(Optional.of(session));
        when(session.isOpen()).thenReturn(true);
        when(pushApi.pushToUser(anyLong(), any())).thenReturn(PushResult.pending("APNs 占位"));

        // 不 confirmAck → 150ms 后超时 → 转 push
        deliveryService.deliver(1L, 99L, List.of("hi"));

        verify(pushApi).pushToUser(eq(1L), any(PushPayload.class));
    }

    @Test
    void deliver_offline_should_push_directly() {
        when(messageService.saveAiMessage(eq(1L), any())).thenReturn(entityWithId(10L, "hi"));
        when(sessionManager.getSession(1L)).thenReturn(Optional.empty());
        when(pushApi.pushToUser(anyLong(), any())).thenReturn(PushResult.pending("APNs 占位"));

        deliveryService.deliver(1L, 99L, List.of("hi"));

        verify(pushApi).pushToUser(eq(1L), any(PushPayload.class));
        verify(messageService, never()).toData(any()); // 离线不推 WS，不需要 toData
    }

    @Test
    void deliver_push_failure_should_not_throw() {
        when(messageService.saveAiMessage(eq(1L), any())).thenReturn(entityWithId(10L, "hi"));
        when(sessionManager.getSession(1L)).thenReturn(Optional.empty());
        when(pushApi.pushToUser(anyLong(), any())).thenThrow(new RuntimeException("APNs down"));

        // 不应抛出（失败靠 L4 sync 兜底）
        List<Long> ids = deliveryService.deliver(1L, 99L, List.of("hi"));

        assertThat(ids).containsExactly(10L);
    }

    @Test
    void deliver_push_returns_failed_should_log_error_not_throw() {
        when(messageService.saveAiMessage(eq(1L), any())).thenReturn(entityWithId(10L, "hi"));
        when(sessionManager.getSession(1L)).thenReturn(Optional.empty());
        // pushApi 正常返回 FAILED（非抛异常路径）→ 应记 ERROR 但不抛、正常返回 id
        when(pushApi.pushToUser(anyLong(), any())).thenReturn(PushResult.failed("APNs 凭证缺失"));

        Logger logger = (Logger) LoggerFactory.getLogger(DeliveryService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            List<Long> ids = deliveryService.deliver(1L, 99L, List.of("hi"));

            assertThat(ids).containsExactly(10L);
            verify(pushApi).pushToUser(eq(1L), any(PushPayload.class));
            // spec §7.1：PushResult.status=FAILED 也要记 ERROR 日志（不只是抛异常路径）
            assertThat(appender.list)
                    .anyMatch(e -> e.getLevel() == Level.ERROR && e.getFormattedMessage().contains("APNs 凭证缺失"));
        } finally {
            logger.detachAppender(appender);
        }
    }
}
