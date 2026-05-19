package com.sanyan.character.internal.intimacy;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 把 IntimacyEvent 算成 delta（涨分值）。
 *
 * <p>规则：
 * <ul>
 *   <li>MESSAGE_SENT：+messageSent（默认 1），但受每日封顶（默认 50）限制——超过封顶后返回 0</li>
 *   <li>DAILY_LOGIN：+dailyLogin（默认 10）+ min(streak × streakBonusPerDay, streakBonusCap)（封顶 50）</li>
 *   <li>PLOT_MILESTONE：直接用 event.payloadInt（rule 携带的 delta）</li>
 *   <li>AI_QUALITY_BONUS：min(event.payloadInt, qualityMaxScore)（封顶 20）</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class IntimacyCalculator {
    private final IntimacyProperties props;
    private final DailyBehaviorCounter dailyCounter;

    public int compute(IntimacyEvent event) {
        return switch (event.type()) {
            case MESSAGE_SENT -> {
                int consumed = dailyCounter.consumedToday(event.userId());
                int remaining = props.getDelta().getMessageDailyCap() - consumed;
                yield Math.max(0, Math.min(props.getDelta().getMessageSent(), remaining));
            }
            case DAILY_LOGIN -> {
                int streak = event.payloadInt();
                int bonus = Math.min(streak * props.getDelta().getStreakBonusPerDay(),
                                     props.getDelta().getStreakBonusCap());
                yield props.getDelta().getDailyLogin() + bonus;
            }
            case PLOT_MILESTONE  -> event.payloadInt();
            case AI_QUALITY_BONUS -> Math.min(event.payloadInt(), props.getAi().getQualityMaxScore());
        };
    }
}
