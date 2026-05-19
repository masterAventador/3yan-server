package com.sanyan.character.dto;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RelationshipDtoTest {
    @Test
    void should_be_record_with_7_fields() {
        var dto = new RelationshipDto(1L, 2L, 250, 1, "朋友", 300, 0.75);
        assertThat(dto.userId()).isEqualTo(1L);
        assertThat(dto.characterId()).isEqualTo(2L);
        assertThat(dto.intimacyScore()).isEqualTo(250);
        assertThat(dto.currentStage()).isEqualTo(1);
        assertThat(dto.currentStageName()).isEqualTo("朋友");
        assertThat(dto.nextStageThreshold()).isEqualTo(300);
        assertThat(dto.percentToNextStage()).isEqualTo(0.75);
    }
}
