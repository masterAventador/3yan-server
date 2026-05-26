package com.sanyan.common.ws;

import com.sanyan.common.cache.KvCache;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * 用户最后活跃时间追踪。失联召回（Plan 4 b 场景）据此判断 24h/3d/7d 阶梯。
 *
 * <p>Redis key {@code user:last_active:{userId}} → ISO-8601 时间戳字符串，TTL 30 天。
 * 走 {@link KvCache} 封装（禁止业务直接碰 RedisTemplate）。粗粒度判断，Redis 丢失
 * 最多漏一次召回，可接受。
 */
@Component
@RequiredArgsConstructor
public class LastActiveTracker {

    private static final String KEY_PREFIX = "user:last_active:";
    private static final Duration TTL = Duration.ofDays(30);

    private final KvCache kvCache;

    /** 记录某用户当前为活跃（覆盖写当前时间）。 */
    public void touch(Long userId) {
        kvCache.set(KEY_PREFIX + userId, Instant.now().toString(), TTL);
    }

    /** 读取最后活跃时间；无记录返回 empty。 */
    public Optional<Instant> lastActiveAt(Long userId) {
        String raw = kvCache.get(KEY_PREFIX + userId);
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(Instant.parse(raw));
    }
}
