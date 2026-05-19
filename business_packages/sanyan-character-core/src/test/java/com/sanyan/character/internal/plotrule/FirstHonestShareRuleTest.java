package com.sanyan.character.internal.plotrule;

import com.sanyan.character.internal.intimacy.IntimacyProperties;
import com.sanyan.chat.dto.MessageDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FirstHonestShareRuleTest {
    IntimacyProperties props;
    FirstHonestShareRule rule;

    @BeforeEach
    void setup() {
        props = new IntimacyProperties();
        props.getRules().setFirstHonestShare(30);
        rule = new FirstHonestShareRule(props);
    }

    @Test
    void should_trigger_when_user_says_emotional_keyword() {
        var msgs = List.of(userMsg("我有点难过"));
        var evt = rule.evaluate(new MessageContext(1L, 1L, msgs, Set.of()));
        assertThat(evt).isPresent();
        assertThat(evt.get().payloadInt()).isEqualTo(30);
        assertThat(evt.get().payloadStr()).isEqualTo("first_honest_share");
    }

    @Test
    void should_not_trigger_on_neutral_message() {
        var msgs = List.of(userMsg("今天天气不错"));
        assertThat(rule.evaluate(new MessageContext(1L, 1L, msgs, Set.of()))).isEmpty();
    }

    @Test
    void should_skip_if_already_triggered() {
        var msgs = List.of(userMsg("我心里好乱"));
        var ctx = new MessageContext(1L, 1L, msgs, Set.of("first_honest_share"));
        assertThat(rule.evaluate(ctx)).isEmpty();
    }

    @Test
    void should_match_multiple_keywords() {
        for (String kw : List.of("我有点难过", "我跟家里", "我心里", "我最近", "我其实", "其实我")) {
            var msgs = List.of(userMsg(kw + "（场景）"));
            assertThat(rule.evaluate(new MessageContext(1L, 1L, msgs, Set.of())))
                    .as("keyword: " + kw)
                    .isPresent();
        }
    }

    @Test
    void should_ignore_ai_messages_even_if_match() {
        var msgs = List.of(new MessageDto(1L, 1L, "ai", "我心里也很想你", LocalDateTime.now()));
        assertThat(rule.evaluate(new MessageContext(1L, 1L, msgs, Set.of()))).isEmpty();
    }

    private MessageDto userMsg(String content) {
        return new MessageDto(1L, 1L, "user", content, LocalDateTime.now());
    }
}
