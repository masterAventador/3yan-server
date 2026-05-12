package com.sanyan.common.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;
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
}
