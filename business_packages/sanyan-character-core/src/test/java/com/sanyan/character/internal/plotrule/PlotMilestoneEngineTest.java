package com.sanyan.character.internal.plotrule;

import com.sanyan.character.internal.intimacy.IntimacyEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PlotMilestoneEngineTest {

    @Test
    void should_aggregate_events_from_all_rules() {
        PlotMilestoneRule alwaysTrigger = ctx -> Optional.of(
                IntimacyEvent.plot(1L, 1L, "always", 10));
        PlotMilestoneRule neverTrigger = ctx -> Optional.empty();

        var engine = new PlotMilestoneEngine(List.of(alwaysTrigger, neverTrigger));

        var events = engine.evaluate(new MessageContext(1L, 1L, List.of(), Set.of()));

        assertThat(events).hasSize(1);
        assertThat(events.get(0).payloadStr()).isEqualTo("always");
    }

    @Test
    void should_skip_rule_already_in_milestones() {
        PlotMilestoneRule trigger = new PlotMilestoneRule() {
            @Override public String ruleId() { return "deep_night_chat"; }
            @Override public Optional<IntimacyEvent> evaluate(MessageContext ctx) {
                return Optional.of(IntimacyEvent.plot(1L, 1L, ruleId(), 50));
            }
        };
        var engine = new PlotMilestoneEngine(List.of(trigger));
        var ctx = new MessageContext(1L, 1L, List.of(), Set.of("deep_night_chat"));

        assertThat(engine.evaluate(ctx)).isEmpty();
    }

    @Test
    void default_rule_id_should_be_snake_case_without_rule_suffix() {
        PlotMilestoneRule rule = new SomeFancyRule();
        assertThat(rule.ruleId()).isEqualTo("some_fancy");
    }

    static class SomeFancyRule implements PlotMilestoneRule {
        @Override public Optional<IntimacyEvent> evaluate(MessageContext ctx) { return Optional.empty(); }
    }
}
