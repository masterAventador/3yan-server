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

    /** 累计 delta，并续期 TTL 到 36h。 */
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
