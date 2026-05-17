package com.sanyan.common.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KvCacheTest {

    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> ops;

    @Test
    void set_delegatesToRedisWithExpiry() {
        when(redis.opsForValue()).thenReturn(ops);
        KvCache cache = new KvCache(redis);

        cache.set("foo", "bar", Duration.ofMinutes(5));

        verify(ops).set("foo", "bar", Duration.ofMinutes(5));
    }

    @Test
    void get_delegatesToRedis() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get("foo")).thenReturn("bar");
        KvCache cache = new KvCache(redis);

        assertThat(cache.get("foo")).isEqualTo("bar");
    }

    @Test
    void delete_delegatesToRedis() {
        KvCache cache = new KvCache(redis);
        cache.delete("foo");
        verify(redis).delete("foo");
    }

    @Test
    void exists_returnsTrueWhenRedisHasKey() {
        when(redis.hasKey("foo")).thenReturn(Boolean.TRUE);
        KvCache cache = new KvCache(redis);
        assertThat(cache.exists("foo")).isTrue();
    }

    @Test
    void exists_returnsFalseWhenRedisReturnsNull() {
        when(redis.hasKey("foo")).thenReturn(null);
        KvCache cache = new KvCache(redis);
        assertThat(cache.exists("foo")).isFalse();
    }

    @Test
    void get_returnsNullWhenKeyMissing() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get("missing")).thenReturn(null);
        KvCache cache = new KvCache(redis);
        assertThat(cache.get("missing")).isNull();
    }

    @Test
    void set_throwsWhenTtlIsZero() {
        KvCache cache = new KvCache(redis);
        assertThatThrownBy(() -> cache.set("k", "v", Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ttl");
    }

    @Test
    void set_throwsWhenTtlIsNull() {
        KvCache cache = new KvCache(redis);
        assertThatThrownBy(() -> cache.set("k", "v", null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void setIfAbsent_returnsTrueWhenRedisAcquired() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent("foo", "bar", Duration.ofMinutes(5))).thenReturn(Boolean.TRUE);
        KvCache cache = new KvCache(redis);

        boolean acquired = cache.setIfAbsent("foo", "bar", Duration.ofMinutes(5));

        assertThat(acquired).isTrue();
        verify(ops).setIfAbsent("foo", "bar", Duration.ofMinutes(5));
    }

    @Test
    void setIfAbsent_returnsFalseWhenKeyAlreadyExists() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent("foo", "bar", Duration.ofMinutes(5))).thenReturn(Boolean.FALSE);
        KvCache cache = new KvCache(redis);

        boolean acquired = cache.setIfAbsent("foo", "bar", Duration.ofMinutes(5));

        assertThat(acquired).isFalse();
    }

    @Test
    void setIfAbsent_returnsFalseWhenRedisReturnsNull() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent("foo", "bar", Duration.ofMinutes(5))).thenReturn(null);
        KvCache cache = new KvCache(redis);

        // Redis null（连接异常等）也视为未获取——节流场景下宁可重复触发也别永久卡住
        assertThat(cache.setIfAbsent("foo", "bar", Duration.ofMinutes(5))).isFalse();
    }

    @Test
    void setIfAbsent_throwsWhenTtlIsZero() {
        KvCache cache = new KvCache(redis);
        assertThatThrownBy(() -> cache.setIfAbsent("k", "v", Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ttl");
    }

    @Test
    void setIfAbsent_throwsWhenTtlIsNull() {
        KvCache cache = new KvCache(redis);
        assertThatThrownBy(() -> cache.setIfAbsent("k", "v", null))
            .isInstanceOf(NullPointerException.class);
    }
}
