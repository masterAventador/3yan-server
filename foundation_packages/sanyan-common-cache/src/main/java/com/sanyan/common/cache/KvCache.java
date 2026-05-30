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

    /**
     * 原子「取出并删除」（Redis GETDEL）。用于一次性消费的值（短信验证码、nonce、bindTicket jti），
     * 避免「get 后再 delete」非原子导致并发读到同一值都通过。
     *
     * @return key 的旧值；不存在返回 null。
     */
    public String getAndDelete(String key) {
        return redis.opsForValue().getAndDelete(key);
    }

    public void delete(String key) {
        redis.delete(key);
    }

    public boolean exists(String key) {
        return Boolean.TRUE.equals(redis.hasKey(key));
    }

    /**
     * 原子自增；key 不存在时（自增结果为 1）顺带设置 TTL，已存在则只自增不重设 TTL。
     *
     * <p>用于「固定窗口计数」场景，典型用例：Plan 4 proactive 每日已发主动消息数
     * {@code proactive:sent:{userId}:{yyyy-MM-dd}}，当天首次发送计 1 并设 36h TTL，
     * 之后只累加，跨天后 key 自然过期重置。
     *
     * <p>Redis 返回 {@code null}（连接异常等）视为 0，调用方据此判断本次计数失败。
     *
     * @param key      计数 key
     * @param ttlIfNew key 首次创建时设置的过期时间（必须为正）
     * @return 自增后的值；Redis 异常返回 0
     */
    public long increment(String key, Duration ttlIfNew) {
        Objects.requireNonNull(ttlIfNew, "ttl must not be null");
        if (ttlIfNew.isZero() || ttlIfNew.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        Long value = redis.opsForValue().increment(key);
        if (value == null) {
            return 0L;
        }
        if (value == 1L) {
            redis.expire(key, ttlIfNew);
        }
        return value;
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
