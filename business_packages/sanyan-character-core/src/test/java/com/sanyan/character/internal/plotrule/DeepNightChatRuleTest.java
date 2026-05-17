package com.sanyan.character.internal.plotrule;

import com.sanyan.character.internal.intimacy.IntimacyProperties;
import com.sanyan.chat.dto.MessageDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DeepNightChatRuleTest {
    IntimacyProperties props;
    DeepNightChatRule rule;

    @BeforeEach
    void setup() {
        props = new IntimacyProperties();
        props.getRules().setDeepNightChat(50);
        rule = new DeepNightChatRule(props);
    }

    @Test
    void should_trigger_when_user_chatted_3_consecutive_nights() {
        LocalDateTime base = LocalDateTime.of(2026, 5, 18, 23, 0);
        var msgs = List.of(
                userMsgAt(base),
                userMsgAt(base.plusDays(1)),
                userMsgAt(base.plusDays(2))
        );
        var evt = rule.evaluate(new MessageContext(1L, 1L, msgs, Set.of()));
        assertThat(evt).isPresent();
        assertThat(evt.get().payloadInt()).isEqualTo(50);
        assertThat(evt.get().payloadStr()).isEqualTo("deep_night_chat");
    }

    @Test
    void should_not_trigger_with_only_2_nights() {
        LocalDateTime base = LocalDateTime.of(2026, 5, 18, 23, 0);
        var msgs = List.of(userMsgAt(base), userMsgAt(base.plusDays(1)));
        assertThat(rule.evaluate(new MessageContext(1L, 1L, msgs, Set.of()))).isEmpty();
    }

    @Test
    void should_not_trigger_daytime_messages() {
        LocalDateTime base = LocalDateTime.of(2026, 5, 18, 14, 0);
        var msgs = List.of(
                userMsgAt(base),
                userMsgAt(base.plusDays(1)),
                userMsgAt(base.plusDays(2))
        );
        assertThat(rule.evaluate(new MessageContext(1L, 1L, msgs, Set.of()))).isEmpty();
    }

    @Test
    void should_trigger_with_early_morning_messages_grouped_to_previous_night() {
        // 23:00 day18 + 01:00 day20（归 day19 night）+ 23:00 day20 — 三晚 day18/day19/day20
        var msgs = List.of(
                userMsgAt(LocalDateTime.of(2026, 5, 18, 23, 0)),  // day 18 night
                userMsgAt(LocalDateTime.of(2026, 5, 20, 1, 0)),   // 归 day 19 night
                userMsgAt(LocalDateTime.of(2026, 5, 20, 23, 0))   // day 20 night
        );
        var evt = rule.evaluate(new MessageContext(1L, 1L, msgs, Set.of()));
        assertThat(evt).isPresent();
    }

    @Test
    void should_ignore_ai_messages() {
        LocalDateTime base = LocalDateTime.of(2026, 5, 18, 23, 0);
        var msgs = List.of(
                aiMsgAt(base),
                aiMsgAt(base.plusDays(1)),
                aiMsgAt(base.plusDays(2))
        );
        assertThat(rule.evaluate(new MessageContext(1L, 1L, msgs, Set.of()))).isEmpty();
    }

    private MessageDto userMsgAt(LocalDateTime t) {
        return new MessageDto(1L, 1L, "user", "hi", t);
    }

    private MessageDto aiMsgAt(LocalDateTime t) {
        return new MessageDto(1L, 1L, "ai", "hi", t);
    }
}
