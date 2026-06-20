package com.sanyan.common.util;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

class RelativeTimeTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final Instant NOW = Instant.parse("2026-06-17T12:00:00Z");

    @Test
    void describe_buckets() {
        assertThat(RelativeTime.describe(NOW.minus(30, ChronoUnit.MINUTES), NOW, SH)).isEqualTo("刚刚");
        assertThat(RelativeTime.describe(NOW.minus(5, ChronoUnit.HOURS), NOW, SH)).isEqualTo("今天");
        assertThat(RelativeTime.describe(NOW.minus(1, ChronoUnit.DAYS), NOW, SH)).isEqualTo("昨天");
        assertThat(RelativeTime.describe(NOW.minus(3, ChronoUnit.DAYS), NOW, SH)).isEqualTo("3天前");
        assertThat(RelativeTime.describe(NOW.minus(8, ChronoUnit.DAYS), NOW, SH)).isEqualTo("约1周前");
        assertThat(RelativeTime.describe(NOW.minus(25, ChronoUnit.DAYS), NOW, SH)).isEqualTo("约3周前");
        assertThat(RelativeTime.describe(NOW.minus(60, ChronoUnit.DAYS), NOW, SH)).isEqualTo("约2个月前");
    }

    @Test
    void describe_future_or_null_is_safe() {
        assertThat(RelativeTime.describe(NOW.plus(1, ChronoUnit.DAYS), NOW, SH)).isEqualTo("刚刚");
        assertThat(RelativeTime.describe(null, NOW, SH)).isEqualTo("");
        assertThat(RelativeTime.describe(NOW, null, SH)).isEqualTo("");
        assertThat(RelativeTime.describe(NOW, NOW, null)).isEqualTo("");
    }

    /**
     * 核心回归用例：按日历日判定，不按经过时长。
     *
     * <p>past = Asia/Shanghai 昨天 20:00（UTC 2026-06-16T12:00Z），
     * now = Asia/Shanghai 今天 09:00（UTC 2026-06-17T01:00Z），间隔仅 13 小时。
     * 旧实现按 elapsed(13h<24h) 会错判成"今天"；正确语义按日历日差=1，应为"昨天"。
     */
    @Test
    void describe_yesterday_evening_to_this_morning_is_yesterday_not_today() {
        Instant past = Instant.parse("2026-06-16T12:00:00Z"); // 当地 06-16 20:00
        Instant now = Instant.parse("2026-06-17T01:00:00Z");  // 当地 06-17 09:00
        assertThat(RelativeTime.describe(past, now, SH)).isEqualTo("昨天");
    }

    /** 同一日历日内即使间隔超过 1 小时（当地 01:00→23:00，22h），仍是"今天"。 */
    @Test
    void describe_same_calendar_day_over_one_hour_is_today() {
        Instant past = Instant.parse("2026-06-16T17:00:00Z"); // 当地 06-17 01:00
        Instant now = Instant.parse("2026-06-17T15:00:00Z");  // 当地 06-17 23:00
        assertThat(RelativeTime.describe(past, now, SH)).isEqualTo("今天");
    }

    /** 分桶边界：calDays 恰好 6 → "6天前"，7 → "约1周前"。 */
    @Test
    void describe_week_boundary() {
        Instant now = Instant.parse("2026-06-17T01:00:00Z"); // 当地 06-17 09:00
        // 当地 06-11 09:00：日历日差 6
        assertThat(RelativeTime.describe(Instant.parse("2026-06-11T01:00:00Z"), now, SH)).isEqualTo("6天前");
        // 当地 06-10 09:00：日历日差 7
        assertThat(RelativeTime.describe(Instant.parse("2026-06-10T01:00:00Z"), now, SH)).isEqualTo("约1周前");
    }

    /** 分桶边界：calDays 恰好 29 → "约4周前"，30 → "约1个月前"。 */
    @Test
    void describe_month_boundary() {
        Instant now = Instant.parse("2026-06-17T01:00:00Z"); // 当地 06-17 09:00
        // 当地 05-19 09:00：日历日差 29
        assertThat(RelativeTime.describe(Instant.parse("2026-05-19T01:00:00Z"), now, SH)).isEqualTo("约4周前");
        // 当地 05-18 09:00：日历日差 30
        assertThat(RelativeTime.describe(Instant.parse("2026-05-18T01:00:00Z"), now, SH)).isEqualTo("约1个月前");
    }
}
