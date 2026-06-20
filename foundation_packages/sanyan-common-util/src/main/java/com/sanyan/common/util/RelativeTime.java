package com.sanyan.common.util;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * 把过去某时刻相对"现在"的间隔渲染成中文口语相对时间（纯静态，无状态）。
 *
 * <p>用于把记忆片段的发生时间喂给 LLM 时附"多久以前"，避免 LLM 把陈旧记忆当成"今天/刚才"。
 * 分桶按<b>日历日</b>判定（带时区），不是按经过时长（elapsed duration）—— 否则昨晚 20:00
 * 的记忆在今早 9:00（间隔仅 13h）会被错判成"今天"。
 * 分桶：&lt;1h 刚刚 / 当天 今天 / 相差 1 日历日 昨天 / 2..6 日 N天前 / 7..29 日 约N周前 / 否则 约N个月前。
 */
public final class RelativeTime {

    private RelativeTime() {}

    /** 同一日历日内，不足该分钟数视为"刚刚"，否则"今天"。 */
    private static final long MINUTES_PER_HOUR = 60;
    /** 一周天数，周分桶阈值（2..6 天用"N天前"，>=7 天起进入"约N周前"）。 */
    private static final int DAYS_PER_WEEK = 7;
    /** 近似一个月的天数，月分桶阈值（>=30 天进入"约N个月前"）。30 为近似值，非精确日历月。 */
    private static final int DAYS_PER_MONTH_APPROX = 30;

    /**
     * @param past 过去时刻（null → 返回空串，调用方据此跳过时间前缀）
     * @param now  当前时刻（null → 返回空串）
     * @param zone 渲染日历日所用时区（null → 返回空串）
     * @return 中文相对时间；past 在未来或与 now 相等一律 "刚刚"
     */
    public static String describe(Instant past, Instant now, ZoneId zone) {
        if (past == null || now == null || zone == null) {
            return "";
        }
        if (!past.isBefore(now)) {
            // past 不早于 now（未来时刻或相等）—— 安全兜底
            return "刚刚";
        }

        Duration elapsed = Duration.between(past, now);
        LocalDate pastDate = past.atZone(zone).toLocalDate();
        LocalDate nowDate = now.atZone(zone).toLocalDate();
        long calDays = ChronoUnit.DAYS.between(pastDate, nowDate); // 日历日差，>=0

        if (calDays == 0) {
            // 同一日历日：仅按"是否不足 1 小时"区分"刚刚 / 今天"
            return elapsed.toMinutes() < MINUTES_PER_HOUR ? "刚刚" : "今天";
        }
        if (calDays == 1) {
            return "昨天";
        }
        if (calDays < DAYS_PER_WEEK) {
            return calDays + "天前";
        }
        if (calDays < DAYS_PER_MONTH_APPROX) {
            return "约" + (calDays / DAYS_PER_WEEK) + "周前";
        }
        return "约" + (calDays / DAYS_PER_MONTH_APPROX) + "个月前";
    }
}
