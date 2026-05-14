package com.sanyan.common.cache;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;

@Component
@RequiredArgsConstructor
public class KvCache {
    private final StringRedisTemplate redis;

    public void set(String key, String value, Duration ttl) {
        Objects.requireNonNull(ttl, "ttl must not be null");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        redis.opsForValue().set(key, value, ttl);
    }

    public String get(String key) {
        return redis.opsForValue().get(key);
    }

    public void delete(String key) {
        redis.delete(key);
    }

    public boolean exists(String key) {
        return Boolean.TRUE.equals(redis.hasKey(key));
    }

    /**
     * 原子 SETNX + EX：当 key 不存在时写入 value 并设 TTL，返回是否「获取成功」。
     *
     * <p>用于「短时间窗口节流 / 一次性触发」类场景。典型用例：
     * Plan 2 Memory profile 抽取 listener 用本方法把同一 user/character 的触发频率限制在 5 分钟一次。
     *
     * <p>返回 {@code false} 的两种情况都视为「未获取」：
     * <ul>
     *   <li>key 已存在（节流命中）</li>
     *   <li>Redis 返回 null（连接异常等）—— 节流场景下宁可让本次跳过，等下次重新尝试</li>
     * </ul>
     */
    public boolean setIfAbsent(String key, String value, Duration ttl) {
        Objects.requireNonNull(ttl, "ttl must not be null");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        Boolean acquired = redis.opsForValue().setIfAbsent(key, value, ttl);
        return Boolean.TRUE.equals(acquired);
    }
}
