package com.sanyan.websocket;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.socket.WebSocketSession;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SessionManagerTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private WebSocketSession wsSession;

    private SessionManager sessionManager;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        sessionManager = new SessionManager(redisTemplate);
    }

    @Test
    void shouldRegisterAndRetrieveSession() {
        when(wsSession.isOpen()).thenReturn(true);
        sessionManager.register(1L, wsSession);

        assertThat(sessionManager.getSession(1L)).isPresent();
        verify(valueOps).set("ws:online:1", "1");
    }

    @Test
    void shouldRemoveSession() {
        sessionManager.register(1L, wsSession);
        sessionManager.remove(1L);

        assertThat(sessionManager.getSession(1L)).isEmpty();
        verify(redisTemplate).delete("ws:online:1");
    }

    @Test
    void shouldCheckOnlineStatus() {
        when(valueOps.get("ws:online:42")).thenReturn("1");
        assertThat(sessionManager.isOnline(42L)).isTrue();

        when(valueOps.get("ws:online:99")).thenReturn(null);
        assertThat(sessionManager.isOnline(99L)).isFalse();
    }
}
