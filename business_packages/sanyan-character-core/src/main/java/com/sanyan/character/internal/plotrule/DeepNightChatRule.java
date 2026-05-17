package com.sanyan.character.internal.plotrule;

import com.sanyan.character.internal.intimacy.IntimacyEvent;
import com.sanyan.character.internal.intimacy.IntimacyProperties;
import com.sanyan.chat.SenderType;
import com.sanyan.chat.dto.MessageDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * DeepNightChatRule：用户在连续 3 个晚上（22:00-01:59）都发过消息 → 触发一次 +50。
 *
 * <p>"晚上"语义：22:00 之后归该日，00:00-01:59 归前一日（"那一晚"）。
 *
 * <p>一次性规则：靠 relationship_milestones 表 + Engine 的 triggeredRuleIds 集合去重。
 */
@Component
@RequiredArgsConstructor
public class DeepNightChatRule implements PlotMilestoneRule {

    private final IntimacyProperties props;

    @Override
    public String ruleId() {
        return "deep_night_chat";
    }

    @Override
    public Optional<IntimacyEvent> evaluate(MessageContext ctx) {
        Set<LocalDate> nightDates = ctx.recentMessages().stream()
                .filter(m -> SenderType.USER.equalsIgnoreCase(m.senderType()))
                .filter(m -> {
                    int hour = m.createdAt().getHour();
                    return hour >= 22 || hour < 2;
                })
                .map(m -> {
                    LocalDateTime t = m.createdAt();
                    return t.getHour() < 2 ? t.toLocalDate().minusDays(1) : t.toLocalDate();
                })
                .collect(Collectors.toSet());

        if (nightDates.size() < 3) {
            return Optional.empty();
        }

        List<LocalDate> sorted = nightDates.stream().sorted().toList();
        for (int i = 0; i <= sorted.size() - 3; i++) {
            if (sorted.get(i).plusDays(1).equals(sorted.get(i + 1))
                    && sorted.get(i + 1).plusDays(1).equals(sorted.get(i + 2))) {
                return Optional.of(IntimacyEvent.plot(
                        ctx.userId(), ctx.characterId(), ruleId(),
                        props.getRules().getDeepNightChat()));
            }
        }
        return Optional.empty();
    }
}
