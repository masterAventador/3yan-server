package com.sanyan.scheduler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class ProactiveSchedulerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void isWithinActiveHours_shouldReturnTrue_whenCurrentHourInRange() throws Exception {
        JsonNode cfg = objectMapper.readTree("{\"active_hours\":[8,22]}");
        assertThat(ProactiveScheduler.isWithinActiveHours(LocalTime.of(10, 0), cfg)).isTrue();
        assertThat(ProactiveScheduler.isWithinActiveHours(LocalTime.of(8, 0), cfg)).isTrue();
        assertThat(ProactiveScheduler.isWithinActiveHours(LocalTime.of(22, 0), cfg)).isTrue();
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
        String configJson = "{\"max_daily\":3,\"min_interval_hours\":2,\"active_hours\":[8,22],"
                + "\"greeting\":{\"enabled\":true,\"slots\":[\"08:00-09:00\",\"12:00-13:00\",\"21:00-22:00\"]},"
                + "\"event_trigger\":{\"enabled\":true,\"idle_hours_threshold\":6},"
                + "\"situational\":{\"enabled\":true,\"trigger_rate\":0.2}}";
        JsonNode cfg = objectMapper.readTree(configJson);

        assertThat(ProactiveScheduler.isWithinActiveHours(LocalTime.of(4, 53), cfg)).isFalse();
        assertThat(ProactiveScheduler.isWithinActiveHours(LocalTime.of(8, 0), cfg)).isTrue();
        assertThat(ProactiveScheduler.isWithinActiveHours(LocalTime.of(22, 0), cfg)).isTrue();
        assertThat(ProactiveScheduler.isWithinActiveHours(LocalTime.of(23, 0), cfg)).isFalse();
    }
}
