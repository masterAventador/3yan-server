package com.sanyan.character.internal.stage;

import com.sanyan.character.internal.intimacy.IntimacyProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StageDefinitionTest {
    IntimacyProperties props;
    StageDefinition def;

    @BeforeEach
    void setup() {
        props = new IntimacyProperties();
        props.getStages().setStrangerEnd(100);
        props.getStages().setFriendEnd(300);
        props.getStages().setAmbiguousEnd(600);
        props.getStages().setLoverEnd(1000);
        def = new StageDefinition(props);
    }

    @Test
    void stage_for_score_boundary() {
        assertThat(def.stageFor(0)).isEqualTo(0);
        assertThat(def.stageFor(99)).isEqualTo(0);
        assertThat(def.stageFor(100)).isEqualTo(1);
        assertThat(def.stageFor(299)).isEqualTo(1);
        assertThat(def.stageFor(300)).isEqualTo(2);
        assertThat(def.stageFor(600)).isEqualTo(3);
        assertThat(def.stageFor(1000)).isEqualTo(4);
        assertThat(def.stageFor(99999)).isEqualTo(4);
    }

    @Test
    void stage_name_should_be_chinese() {
        assertThat(def.nameOf(0)).isEqualTo("陌生人");
        assertThat(def.nameOf(2)).isEqualTo("暧昧");
        assertThat(def.nameOf(4)).isEqualTo("老夫老妻");
    }

    @Test
    void next_threshold_should_match_stage_end() {
        assertThat(def.nextThresholdOf(0)).isEqualTo(100);
        assertThat(def.nextThresholdOf(1)).isEqualTo(300);
        assertThat(def.nextThresholdOf(2)).isEqualTo(600);
        assertThat(def.nextThresholdOf(3)).isEqualTo(1000);
        assertThat(def.nextThresholdOf(4)).isEqualTo(Integer.MAX_VALUE);
    }
}
