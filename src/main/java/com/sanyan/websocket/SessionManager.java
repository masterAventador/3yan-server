package com.sanyan.websocket;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
public class SessionManager {

    private final StringRedisTemplate redisTemplate;
    private final Map<Long, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private static final String ONLINE_PREFIX = "ws:online:";

    public void register(Long userId, WebSocketSession session) {
        sessions.put(userId, session);
        redisTemplate.opsForValue().set(ONLINE_PREFIX + userId, "1");
    }

    public void remove(Long userId) {
        sessions.remove(userId);
        redisTemplate.delete(ONLINE_PREFIX + userId);
    }

    public Optional<WebSocketSession> getSession(Long userId) {
        WebSocketSession session = sessions.get(userId);
        if (session != null && session.isOpen()) {
            return Optional.of(session);
        }
        if (session != null) {
            remove(userId);
        }
        return Optional.empty();
    }

    public boolean isOnline(Long userId) {
        return "1".equals(redisTemplate.opsForValue().get(ONLINE_PREFIX + userId));
    }
}
