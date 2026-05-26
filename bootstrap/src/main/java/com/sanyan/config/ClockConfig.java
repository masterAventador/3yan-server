package com.sanyan.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 全局 Clock Bean（Plan 4 F5 引入）。
 *
 * <p>注入 {@link java.time.Clock} 而非直接用 {@code Instant.now()} / {@code LocalDate.now()}，
 * 便于在单测中通过 {@link Clock#fixed} 固定时间，精准断言 salient_at 派生时间。
 *
 * <p>生产使用系统默认时区（单实例 dogfood 够用）。
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
