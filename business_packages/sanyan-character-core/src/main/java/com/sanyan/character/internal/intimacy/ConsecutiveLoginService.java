package com.sanyan.character.internal.intimacy;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Map;

/**
 * 连续登录天数（Redis）。
 *
 * <p>key 格式：{@code streak:user:<userId>} —— Redis Hash，含 streak（数字） + last_date（yyyy-MM-dd）
 *
 * <p>语义：
 * <ul>
 *   <li>当日首次 recordLogin → 与昨天连续则 streak++，否则重置为 1，返回 isFirstToday=true</li>
 *   <li>当日重复 recordLogin → 不变，返回 isFirstToday=false</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ConsecutiveLoginService {

    public record LoginResult(boolean isFirstToday, int streak) {}

    private final StringRedisTemplate redis;

    public LoginResult recordLogin(Long userId, LocalDate today) {
        String key = "streak:user:" + userId;
        Map<Object, Object> entries = redis.opsForHash().entries(key);

        String lastDateStr = (String) entries.get("last_date");
        int prevStreak = entries.containsKey("streak")
                ? Integer.parseInt((String) entries.get("streak"))
                : 0;

        if (today.toString().equals(lastDateStr)) {
            return new LoginResult(false, prevStreak);
        }

        int newStreak;
        if (lastDateStr != null && LocalDate.parse(lastDateStr).plusDays(1).equals(today)) {
            newStreak = prevStreak + 1;
        } else {
            newStreak = 1;
        }

        redis.opsForHash().put(key, "streak", String.valueOf(newStreak));
        redis.opsForHash().put(key, "last_date", today.toString());
        return new LoginResult(true, newStreak);
    }
}
