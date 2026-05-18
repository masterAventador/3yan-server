package com.sanyan.character.internal.intimacy;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 每日行为分封顶计数器。
 *
 * <p>key 格式：{@code behavior:user:<userId>:date:<yyyy-MM-dd>}
 * <p>TTL：36 小时（防止跨日累积污染）
 */
@Component
@RequiredArgsConstructor
public class DailyBehaviorCounter {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final StringRedisTemplate redis;

    /** 返回 userId 今日累计消耗的 delta（不存在则 0）。 */
    public int consumedToday(Long userId) {
        String v = redis.opsForValue().get(keyFor(userId));
        return v == null ? 0 : Integer.parseInt(v);
    }

    /**
     * 累计 delta，并续期 TTL 到 36h。
     *
     * <p><b>已知接受的非原子风险：</b>{@code INCR} 与 {@code EXPIRE} 是两条独立命令，
     * 若进程在两者之间崩溃，key 可能残留无 TTL（永不过期）。
     * 这是已知风险，但不影响业务正确性：
     * <ul>
     *   <li>该 key 按日期命名，下一天自然换新 key，旧 key 即使无 TTL 也不再被写入</li>
     *   <li>TTL=36h 足以覆盖跨午夜的边界情况，崩溃窗口极窄（两条命令间隔 &lt;1ms）</li>
     *   <li>若需严格原子，可用 Lua 脚本或 Redis SET + EXPIRE PX 替代——留为 Plan 5 优化项</li>
     * </ul>
     */
    public void incr(Long userId, int delta) {
        if (delta <= 0) return;
        String key = keyFor(userId);
        redis.opsForValue().increment(key, delta);
        redis.expire(key, Duration.ofHours(36));
    }

    private static String keyFor(Long userId) {
        return "behavior:user:" + userId + ":date:" + LocalDate.now().format(FMT);
    }
}
