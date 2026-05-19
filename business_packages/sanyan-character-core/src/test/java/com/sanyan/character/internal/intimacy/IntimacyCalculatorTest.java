package com.sanyan.character.internal.intimacy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class IntimacyCalculatorTest {
    @Mock DailyBehaviorCounter dailyCounter;
    IntimacyProperties props;
    IntimacyCalculator calculator;

    @BeforeEach
    void setup() {
        props = new IntimacyProperties();
        // 配置区已经有默认值（messageSent=1, messageDailyCap=50, dailyLogin=10, streakBonusPerDay=5, streakBonusCap=50, qualityMaxScore=20）
        calculator = new IntimacyCalculator(props, dailyCounter);
    }

    @Test
    void message_sent_should_yield_1() {
        when(dailyCounter.consumedToday(1L)).thenReturn(0);
        var event = IntimacyEvent.messageSent(1L, 1L);
        assertThat(calculator.compute(event)).isEqualTo(1);
    }

    @Test
    void message_sent_should_be_zero_after_daily_cap() {
        when(dailyCounter.consumedToday(1L)).thenReturn(50);
        var event = IntimacyEvent.messageSent(1L, 1L);
        assertThat(calculator.compute(event)).isZero();
    }

    @Test
    void daily_login_with_streak_3() {
        var event = IntimacyEvent.dailyLogin(1L, 1L, 3);
        // dailyLogin(10) + min(3*5, 50) = 10 + 15 = 25
        assertThat(calculator.compute(event)).isEqualTo(25);
    }

    @Test
    void daily_login_should_cap_streak_bonus() {
        var event = IntimacyEvent.dailyLogin(1L, 1L, 100);
        // dailyLogin(10) + min(100*5, 50) = 10 + 50 = 60
        assertThat(calculator.compute(event)).isEqualTo(60);
    }

    @Test
    void plot_milestone_carries_explicit_delta() {
        var event = IntimacyEvent.plot(1L, 1L, "deep_night_chat", 50);
        assertThat(calculator.compute(event)).isEqualTo(50);
    }

    @Test
    void ai_quality_carries_score() {
        var event = IntimacyEvent.aiQuality(1L, 1L, 15);
        assertThat(calculator.compute(event)).isEqualTo(15);
    }

    @Test
    void ai_quality_should_cap_at_max() {
        var event = IntimacyEvent.aiQuality(1L, 1L, 999);
        assertThat(calculator.compute(event)).isEqualTo(20);
    }
}
