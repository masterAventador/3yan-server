package com.sanyan.scheduler;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class ProactiveSchedulerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setupLogCapture() {
        Logger logger = (Logger) LoggerFactory.getLogger(ProactiveScheduler.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void teardownLogCapture() {
        Logger logger = (Logger) LoggerFactory.getLogger(ProactiveScheduler.class);
        logger.detachAppender(logAppender);
    }

    @Test
    void isWithinActiveHours_shouldReturnTrue_whenCurrentHourInRange() throws Exception {
        JsonNode cfg = objectMapper.readTree("{\"active_hours\":[8,22]}");
        assertThat(ProactiveScheduler.isWithinActiveHours(LocalTime.of(10, 0), cfg)).isTrue();
        assertThat(ProactiveScheduler.isWithinActiveHours(LocalTime.of(8, 0), cfg)).isTrue();
        assertThat(ProactiveScheduler.isWithinActiveHours(LocalTime.of(22, 0), cfg)).isTrue();
        assertThat(ProactiveScheduler.isWithinActiveHours(LocalTime.of(22, 59), cfg)).isTrue();
    }

    @Test
    void isWithinActiveHours_shouldReturnFalse_whenCurrentHourOutsideRange() throws Exception {
        JsonNode cfg = objectMapper.readTree("{\"active_hours\":[8,22]}");
        assertThat(ProactiveScheduler.isWithinActiveHours(LocalTime.of(4, 53), cfg)).isFalse();
        assertThat(ProactiveScheduler.isWithinActiveHours(LocalTime.of(7, 59), cfg)).isFalse();
        assertThat(ProactiveScheduler.isWithinActiveHours(LocalTime.of(23, 0), cfg)).isFalse();
    }

    @Test
    void isWithinActiveHours_shouldReturnTrue_whenConfigMissing() throws Exception {
        JsonNode cfg = objectMapper.readTree("{}");
        assertThat(ProactiveScheduler.isWithinActiveHours(LocalTime.of(4, 0), cfg)).isTrue();
    }

    @Test
    void dataSqlConfig_shouldExcludeEarlyMorning() throws Exception {
        String sqlContent = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/resources/data.sql"));
        // proactive_config JSON 在 data.sql 里被单引号包着，形如:
        // ...,'{"max_daily":3,...,"situational":{...}}',...
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("'(\\{[^']*\"max_daily\"[^']*\\})'")
                .matcher(sqlContent);
        assertThat(m.find()).as("应能在 data.sql 找到 proactive_config JSON").isTrue();
        String configJson = m.group(1);

        JsonNode cfg = objectMapper.readTree(configJson);

        // 凌晨 4:53 必被挡 → 这是修这个 bug 的核心断言
        assertThat(ProactiveScheduler.isWithinActiveHours(LocalTime.of(4, 53), cfg))
                .as("data.sql 配置必须能挡住凌晨 4:53").isFalse();
        // 同时确认 active_hours 边界仍然合理
        assertThat(ProactiveScheduler.isWithinActiveHours(LocalTime.of(8, 0), cfg)).isTrue();
        assertThat(ProactiveScheduler.isWithinActiveHours(LocalTime.of(22, 0), cfg)).isTrue();
        assertThat(ProactiveScheduler.isWithinActiveHours(LocalTime.of(23, 0), cfg)).isFalse();
    }

    @Test
    void isWithinActiveHours_shouldReturnTrueAndWarn_whenHoursOutOfRange() throws Exception {
        JsonNode cfg = objectMapper.readTree("{\"active_hours\":[-1,25]}");
        boolean result = ProactiveScheduler.isWithinActiveHours(LocalTime.of(10, 0), cfg);
        assertThat(result).as("越界配置应 fail-safe 放行").isTrue();
        assertThat(logAppender.list)
                .as("应打 warn 日志提示 active_hours 越界")
                .anyMatch(e -> e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains("越界"));
    }

    @Test
    void isWithinActiveHours_shouldReturnFalseAndWarn_whenHoursReversed() throws Exception {
        JsonNode cfg = objectMapper.readTree("{\"active_hours\":[22,8]}");
        boolean result = ProactiveScheduler.isWithinActiveHours(LocalTime.of(10, 0), cfg);
        assertThat(result).as("反向配置应 fail-closed 拦截").isFalse();
        assertThat(logAppender.list)
                .as("应打 warn 日志提示 active_hours 反向")
                .anyMatch(e -> e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains("反向"));
    }
}
