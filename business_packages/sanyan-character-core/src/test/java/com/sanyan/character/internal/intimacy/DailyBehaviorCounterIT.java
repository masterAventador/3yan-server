package com.sanyan.character.internal.intimacy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D3：DailyBehaviorCounter Redis 集成测试。
 *
 * <p>使用 GenericContainer + redis:7-alpine 起容器；DynamicPropertySource 注入
 * spring.data.redis.* 端口。
 */
@SpringBootTest(classes = {DailyBehaviorCounterIT.TestConfig.class, DailyBehaviorCounter.class},
        properties = {"spring.main.web-application-type=none"})
@Testcontainers
class DailyBehaviorCounterIT {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @TestConfiguration
    @Import(RedisAutoConfiguration.class)
    static class TestConfig {
    }

    @Autowired
    DailyBehaviorCounter counter;

    @Autowired
    StringRedisTemplate redisTemplate;

    @BeforeEach
    void cleanUp() {
        // 清空 Redis 防止用例间污染
        var conn = redisTemplate.getRequiredConnectionFactory().getConnection();
        conn.serverCommands().flushDb();
    }

    @Test
    void incr_and_consumedToday_should_match() {
        counter.incr(1L, 5);
        counter.incr(1L, 3);
        assertThat(counter.consumedToday(1L)).isEqualTo(8);
    }

    @Test
    void different_users_should_not_interfere() {
        counter.incr(1L, 10);
        counter.incr(2L, 5);
        assertThat(counter.consumedToday(1L)).isEqualTo(10);
        assertThat(counter.consumedToday(2L)).isEqualTo(5);
    }

    @Test
    void consumedToday_should_be_zero_when_no_record() {
        assertThat(counter.consumedToday(999L)).isZero();
    }

    @Test
    void incr_zero_or_negative_should_be_noop() {
        counter.incr(1L, 0);
        counter.incr(1L, -5);
        assertThat(counter.consumedToday(1L)).isZero();
    }
}
