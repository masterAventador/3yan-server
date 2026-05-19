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

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = {ConsecutiveLoginServiceIT.TestConfig.class, ConsecutiveLoginService.class},
        properties = {"spring.main.web-application-type=none"})
@Testcontainers
class ConsecutiveLoginServiceIT {

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
    static class TestConfig {}

    @Autowired ConsecutiveLoginService svc;
    @Autowired StringRedisTemplate redisTemplate;

    @BeforeEach
    void cleanUp() {
        redisTemplate.getRequiredConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @Test
    void first_login_starts_streak_1() {
        var result = svc.recordLogin(1L, LocalDate.now());
        assertThat(result.isFirstToday()).isTrue();
        assertThat(result.streak()).isEqualTo(1);
    }

    @Test
    void second_call_same_day_is_idempotent() {
        svc.recordLogin(1L, LocalDate.now());
        var result = svc.recordLogin(1L, LocalDate.now());
        assertThat(result.isFirstToday()).isFalse();
        assertThat(result.streak()).isEqualTo(1);
    }

    @Test
    void consecutive_days_should_increment_streak() {
        svc.recordLogin(1L, LocalDate.of(2026, 5, 18));
        var d2 = svc.recordLogin(1L, LocalDate.of(2026, 5, 19));
        assertThat(d2.streak()).isEqualTo(2);
        assertThat(d2.isFirstToday()).isTrue();

        var d3 = svc.recordLogin(1L, LocalDate.of(2026, 5, 20));
        assertThat(d3.streak()).isEqualTo(3);
    }

    @Test
    void gap_should_reset_streak() {
        svc.recordLogin(1L, LocalDate.of(2026, 5, 18));
        var afterGap = svc.recordLogin(1L, LocalDate.of(2026, 5, 22));
        assertThat(afterGap.streak()).isEqualTo(1);
        assertThat(afterGap.isFirstToday()).isTrue();
    }
}
