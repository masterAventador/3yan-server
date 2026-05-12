package com.sanyan.common.util;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 模拟真人打字节奏的延时计算（Plan-1 F6）。
 *
 * - typing delay：1500ms 基础 + 80ms/字，±20% 抖动，封顶 8s
 * - 条间停顿：800-1500ms 随机
 */
public final class TypingDelayCalculator {

    private static final long BASE_MS = 1500L;
    private static final long PER_CHAR_MS = 80L;
    private static final long MAX_TYPING_MS = 8000L;
    private static final long MIN_GAP_MS = 800L;
    private static final long MAX_GAP_MS = 1500L;
    private static final long EMPTY_FALLBACK_MS = 1000L;

    private TypingDelayCalculator() {}

    public static long calculateTypingDelay(String content) {
        if (content == null || content.isEmpty()) return EMPTY_FALLBACK_MS;
        long base = BASE_MS + (long) content.length() * PER_CHAR_MS;
        double jitter = 0.8 + ThreadLocalRandom.current().nextDouble() * 0.4; // [0.8, 1.2)
        long delay = (long) (base * jitter);
        return Math.min(delay, MAX_TYPING_MS);
    }

    public static long calculateInterMessageGap() {
        return ThreadLocalRandom.current().nextLong(MIN_GAP_MS, MAX_GAP_MS + 1);
    }
}
