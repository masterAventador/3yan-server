package com.sanyan.character.event;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CharacterEventContractTest {

    @Test
    void intimacy_changed_event_should_be_record_with_6_fields() {
        var e = new IntimacyChangedEvent(1L, 2L, 10, 15, 5, "MESSAGE_SENT");
        assertThat(e.userId()).isEqualTo(1L);
        assertThat(e.characterId()).isEqualTo(2L);
        assertThat(e.oldScore()).isEqualTo(10);
        assertThat(e.newScore()).isEqualTo(15);
        assertThat(e.delta()).isEqualTo(5);
        assertThat(e.reason()).isEqualTo("MESSAGE_SENT");
    }

    @Test
    void stage_transition_event_should_be_record_with_4_fields() {
        var e = new StageTransitionEvent(1L, 2L, 0, 1);
        assertThat(e.userId()).isEqualTo(1L);
        assertThat(e.characterId()).isEqualTo(2L);
        assertThat(e.fromStage()).isZero();
        assertThat(e.toStage()).isEqualTo(1);
    }
}
