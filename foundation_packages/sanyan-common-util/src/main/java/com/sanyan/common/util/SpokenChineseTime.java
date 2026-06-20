package com.sanyan.common.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;

/**
 * 中文口语时间格式化（纯静态，无状态）。
 *
 * <p>从 chat-core {@code AiService} 抽出下沉到基础层：主对话（{@code AiService}）与主动推送
 * （{@code ProactivePromptBuilder}）都需要把"当前时间"喂给 LLM，且要附中文口语版本减少
 * LLM 数字→自然语言时间的幻觉（实测 01:30 会被说成"两点多"）。
 */
public final class SpokenChineseTime {

    private SpokenChineseTime() {}

    /**
     * 喂给 LLM 的"当前时间"锚点前缀（带尾部空格，拼成 {@code CURRENT_TIME_PREFIX + label(now)}）。
     * 单点定义：主对话 {@code AiService} 与主动推送 {@code ProactivePromptBuilder} 共用，
     * 且 {@code AiService.TIME_AWARENESS_GUIDE} 引导段文案与此前缀强耦合，避免日后改前缀漏改导致引导失效。
     */
    public static final String CURRENT_TIME_PREFIX = "[当前时间] ";

    /** 完整中文时间标签格式：yyyy年M月d日 E HH:mm（E=周几）。 */
    private static final DateTimeFormatter LABEL_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy年M月d日 E HH:mm", Locale.CHINESE);

    private static final String[] DIGITS =
            {"零", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十", "十一", "十二"};

    /**
     * 完整时间标签 + 口语后缀，如 {@code "2026年6月17日 周三 22:30（晚上十点半）"}。
     * 调用方通常拼成 {@code "[当前时间] " + label(now)}。
     */
    public static String label(LocalDateTime time) {
        Objects.requireNonNull(time, "time must not be null");
        return time.format(LABEL_FORMATTER) + "（" + spoken(time) + "）";
    }

    /** 仅口语时刻，如 {@code "凌晨一点半"} / {@code "下午两点零五分"} / {@code "中午十二点整"}。 */
    public static String spoken(LocalDateTime time) {
        Objects.requireNonNull(time, "time must not be null");
        int hour = time.getHour();
        int minute = time.getMinute();
        String period;
        int hour12;
        if (hour == 0) { period = "凌晨"; hour12 = 12; }
        else if (hour < 6) { period = "凌晨"; hour12 = hour; }
        else if (hour < 12) { period = "上午"; hour12 = hour; }
        else if (hour == 12) { period = "中午"; hour12 = 12; }
        else if (hour < 18) { period = "下午"; hour12 = hour - 12; }
        else { period = "晚上"; hour12 = hour - 12; }

        // 中文口语：2 点习惯说"两点"而非"二点"（分钟里的 2 仍说"二"）
        String hourStr = (hour12 == 2 ? "两" : chineseNumber(hour12)) + "点";
        String minuteStr;
        if (minute == 0) {
            minuteStr = "整";
        } else if (minute == 30) {
            minuteStr = "半";
        } else if (minute < 10) {
            minuteStr = "零" + chineseNumber(minute) + "分";
        } else {
            minuteStr = chineseDoubleDigitNumber(minute) + "分";
        }
        return period + hourStr + minuteStr;
    }

    private static String chineseNumber(int n) {
        if (n >= 0 && n <= 12) return DIGITS[n];
        return String.valueOf(n);  // 兜底（hour12 取值 1-12，minute 个位 0-9，不应该走到这里）
    }

    private static String chineseDoubleDigitNumber(int n) {
        // 10-59 用于分钟（"十" / "十一" / ... / "五十九"）
        if (n < 10) return DIGITS[n];
        if (n == 10) return "十";
        if (n < 20) return "十" + DIGITS[n - 10];
        int tens = n / 10;
        int ones = n % 10;
        return DIGITS[tens] + "十" + (ones == 0 ? "" : DIGITS[ones]);
    }
}
