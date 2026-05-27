package com.sanyan.common.ws;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 在线用户管理。
 *
 * <p><b>⚠️ 单实例部署前提：</b>真正的 {@link WebSocketSession} 只存在
 * 本进程的 {@link #sessions} 内存 Map。多实例部署时，{@link #isOnline}
 * 虽然依赖 Redis 跨实例可见，但 {@link #getSession} 只能在当前进程命中，
 * 无法把消息推给别的实例持有的连接（会造成主动消息丢失）。
 *
 * <p>TODO（多实例化改造）：
 * <ul>
 *   <li>方案 A：Redis Pub/Sub — 每个实例订阅 {@code ws:push:{userId}} 频道，
 *       需要下行消息时对应实例把自己持有的 session 写入。</li>
 *   <li>方案 B：在 Redis 记录 userId → instanceId 映射，通过 HTTP/RPC 路由到
 *       正确实例。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class SessionManager {

    private final StringRedisTemplate redisTemplate;
    private final Map<Long, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private static final String ONLINE_PREFIX = "ws:online:";
    private static final String ONLINE_VALUE = "1";
    // 在线 TTL：客户端心跳间隔必须 < 90s，否则会误判离线
    private static final Duration ONLINE_TTL = Duration.ofSeconds(90);

    public void register(Long userId, WebSocketSession session) {
        sessions.put(userId, session);
        writeOnlineKey(userId);
    }

    /** 心跳续期：客户端 PING 时刷新在线 TTL（覆盖写同 key 即续期）。 */
    public void refreshOnline(Long userId) {
        writeOnlineKey(userId);
    }

    private void writeOnlineKey(Long userId) {
        redisTemplate.opsForValue().set(ONLINE_PREFIX + userId, ONLINE_VALUE, ONLINE_TTL);
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
