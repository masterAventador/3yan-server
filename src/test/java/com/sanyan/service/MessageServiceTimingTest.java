package com.sanyan.service;

import com.sanyan.util.TypingDelayCalculator;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MessageServiceTimingTest {

    @Test
    void calculateTypingDelay_nullOrEmpty_returnsFixedShortDelay() {
        assertThat(TypingDelayCalculator.calculateTypingDelay(null)).isEqualTo(1000);
        assertThat(TypingDelayCalculator.calculateTypingDelay("")).isEqualTo(1000);
    }

    @RepeatedTest(50)
    void calculateTypingDelay_followsPlanFormula_withinPlusMinus20PercentBand() {
        String content = "今天好开心啊"; // 6 字
        long delay = TypingDelayCalculator.calculateTypingDelay(content);

        long base = 1500 + 6L * 80; // = 1980
        long lo = (long) (base * 0.8);
        long hi = (long) (base * 1.2);
        assertThat(delay).isBetween(lo, hi);
    }

    @RepeatedTest(50)
    void calculateTypingDelay_cappedAt8Seconds() {
        String longContent = "啊".repeat(200);
        long delay = TypingDelayCalculator.calculateTypingDelay(longContent);
        assertThat(delay).isLessThanOrEqualTo(8000);
    }

    @RepeatedTest(50)
    void calculateInterMessageGap_within800To1500Range() {
        long gap = TypingDelayCalculator.calculateInterMessageGap();
        assertThat(gap).isBetween(800L, 1500L);
    }
}
