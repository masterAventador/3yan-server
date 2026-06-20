package com.sanyan.common.util;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class SpokenChineseTimeTest {

    @Test
    void spoken_should_render_chinese_colloquial_time() {
        assertThat(SpokenChineseTime.spoken(LocalDateTime.of(2026, 6, 17, 1, 30))).isEqualTo("凌晨一点半");
        assertThat(SpokenChineseTime.spoken(LocalDateTime.of(2026, 6, 17, 14, 5))).isEqualTo("下午两点零五分");
        assertThat(SpokenChineseTime.spoken(LocalDateTime.of(2026, 6, 17, 12, 0))).isEqualTo("中午十二点整");
        assertThat(SpokenChineseTime.spoken(LocalDateTime.of(2026, 6, 17, 22, 30))).isEqualTo("晚上十点半");
    }

    // 以下 8 个用例从 AiServiceTest 的 toSpokenChineseTime_* 迁移而来（逻辑下沉到本类，覆盖一并迁入）
    @Test
    void spoken_midnight() {
        assertThat(SpokenChineseTime.spoken(LocalDateTime.of(2026, 5, 25, 0, 0))).isEqualTo("凌晨十二点整");
    }

    @Test
    void spoken_earlyMorningWithHalfHour() {
        assertThat(SpokenChineseTime.spoken(LocalDateTime.of(2026, 5, 25, 1, 30))).isEqualTo("凌晨一点半");
    }

    @Test
    void spoken_morningOnTheHour() {
        assertThat(SpokenChineseTime.spoken(LocalDateTime.of(2026, 5, 25, 6, 0))).isEqualTo("上午六点整");
    }

    @Test
    void spoken_noon() {
        assertThat(SpokenChineseTime.spoken(LocalDateTime.of(2026, 5, 25, 12, 0))).isEqualTo("中午十二点整");
    }

    @Test
    void spoken_noonHalfHour() {
        assertThat(SpokenChineseTime.spoken(LocalDateTime.of(2026, 5, 25, 12, 30))).isEqualTo("中午十二点半");
    }

    @Test
    void spoken_afternoonWithSingleDigitMinute() {
        assertThat(SpokenChineseTime.spoken(LocalDateTime.of(2026, 5, 25, 14, 5))).isEqualTo("下午两点零五分");
    }

    @Test
    void spoken_eveningOnTheHour() {
        assertThat(SpokenChineseTime.spoken(LocalDateTime.of(2026, 5, 25, 18, 0))).isEqualTo("晚上六点整");
    }

    @Test
    void spoken_eveningWithDoubleDigitMinute() {
        assertThat(SpokenChineseTime.spoken(LocalDateTime.of(2026, 5, 25, 23, 45))).isEqualTo("晚上十一点四十五分");
    }

    @Test
    void label_should_render_full_chinese_datetime_with_spoken_suffix() {
        // 2026-06-17 是周三
        String label = SpokenChineseTime.label(LocalDateTime.of(2026, 6, 17, 22, 30));
        assertThat(label).contains("2026年6月17日");
        assertThat(label).contains("周三");
        assertThat(label).contains("22:30");
        assertThat(label).contains("（晚上十点半）");
    }
}
