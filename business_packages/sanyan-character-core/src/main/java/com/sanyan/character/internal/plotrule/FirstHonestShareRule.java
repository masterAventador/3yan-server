package com.sanyan.character.internal.plotrule;

import com.sanyan.character.internal.intimacy.IntimacyEvent;
import com.sanyan.character.internal.intimacy.IntimacyProperties;
import com.sanyan.chat.SenderType;
import com.sanyan.chat.dto.MessageDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * FirstHonestShareRule：用户首次发"情感深度分享"消息 → +30（一次性）。
 *
 * <p>关键词清单（命中其一即可）：我有点难过、我跟家里、我心里、我最近、我其实、其实我。
 * <p>调优：dogfood 期可改关键词或换 LLM 语义判断（暂时关键词够用）。
 */
@Component
@RequiredArgsConstructor
public class FirstHonestShareRule implements PlotMilestoneRule {

    private static final List<String> KEYWORDS = List.of(
            "我有点难过", "我跟家里", "我心里", "我最近", "我其实", "其实我"
    );

    private final IntimacyProperties props;

    @Override
    public String ruleId() {
        return "first_honest_share";
    }

    @Override
    public Optional<IntimacyEvent> evaluate(MessageContext ctx) {
        if (ctx.triggeredRuleIds().contains(ruleId())) {
            return Optional.empty();
        }

        boolean hit = ctx.recentMessages().stream()
                .filter(m -> SenderType.USER.equalsIgnoreCase(m.senderType()))
                .map(MessageDto::content)
                .filter(Objects::nonNull)
                .anyMatch(content -> KEYWORDS.stream().anyMatch(content::contains));

        if (!hit) return Optional.empty();
        return Optional.of(IntimacyEvent.plot(
                ctx.userId(), ctx.characterId(), ruleId(),
                props.getRules().getFirstHonestShare()));
    }
}
