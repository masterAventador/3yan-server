package com.sanyan.character.internal.stage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanyan.character.internal.AiCharacterEntity;
import com.sanyan.character.internal.AiCharacterRepository;
import com.sanyan.character.internal.AiCharacterTestFixtures;
import com.sanyan.character.internal.intimacy.IntimacyProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class StageOverrideQueryServiceTest {

    @Mock AiCharacterRepository charRepo;
    StageDefinition stageDef;
    StageOverrideQueryService service;

    @BeforeEach
    void setup() {
        IntimacyProperties props = new IntimacyProperties();
        props.getStages().setStrangerEnd(100);
        props.getStages().setFriendEnd(300);
        props.getStages().setAmbiguousEnd(600);
        props.getStages().setLoverEnd(1000);
        stageDef = new StageDefinition(props);
        service = new StageOverrideQueryService(charRepo, stageDef, new ObjectMapper());
    }

    @Test
    void should_assemble_prompt_segment_from_persona_config() {
        AiCharacterEntity ch = AiCharacterTestFixtures.withPersonaConfig("基底", """
            { "stage_overrides": {
                "2": {"address":"宝","tone_hint":"撒娇","topics_unlock":["想见面"]}
            }}
            """);
        when(charRepo.findById(1L)).thenReturn(Optional.of(ch));

        String segment = service.buildSegment(1L, 2);

        assertThat(segment).contains("当前关系阶段：暧昧");
        assertThat(segment).contains("称呼用户用：宝");
        assertThat(segment).contains("语调：撒娇");
    }

    @Test
    void should_return_blank_when_no_override_for_stage() {
        AiCharacterEntity ch = AiCharacterTestFixtures.withPersonaConfig("基底", "{\"stage_overrides\":{}}");
        when(charRepo.findById(1L)).thenReturn(Optional.of(ch));

        assertThat(service.buildSegment(1L, 0)).isEmpty();
    }

    @Test
    void should_return_blank_when_character_not_found() {
        when(charRepo.findById(99L)).thenReturn(Optional.empty());
        assertThat(service.buildSegment(99L, 2)).isEmpty();
    }

    @Test
    void should_return_blank_on_malformed_json() {
        AiCharacterEntity ch = AiCharacterTestFixtures.withPersonaConfig("基底", "not json");
        when(charRepo.findById(1L)).thenReturn(Optional.of(ch));
        assertThat(service.buildSegment(1L, 2)).isEmpty();
    }
}
