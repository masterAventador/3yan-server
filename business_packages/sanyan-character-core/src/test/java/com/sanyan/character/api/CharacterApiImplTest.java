package com.sanyan.character.api;

import com.sanyan.character.dto.AiCharacterDto;
import com.sanyan.character.dto.RelationshipDto;
import com.sanyan.character.internal.AiCharacterEntity;
import com.sanyan.character.internal.AiCharacterRepository;
import com.sanyan.character.internal.AiCharacterTestFixtures;
import com.sanyan.character.internal.RelationshipEntity;
import com.sanyan.character.internal.RelationshipFetchService;
import com.sanyan.character.internal.RelationshipFindOrCreateService;
import com.sanyan.character.internal.fixtures.RelationshipTestFixtures;
import com.sanyan.character.internal.stage.StageDefinition;
import com.sanyan.character.internal.stage.StageOverrideQueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CharacterApiImplTest {

    @Mock AiCharacterRepository charRepo;
    @Mock RelationshipFindOrCreateService findOrCreate;
    @Mock RelationshipFetchService fetchService;
    @Mock StageOverrideQueryService stageOverrideQuery;
    @Mock StageDefinition stageDef;
    @InjectMocks CharacterApiImpl impl;

    @Test
    void findOrCreateRelationship_should_delegate_and_return_dto() {
        RelationshipEntity rel = RelationshipTestFixtures.relationshipWithScore(1L, 1L, 0);
        when(findOrCreate.findOrCreate(1L, 1L)).thenReturn(rel);
        when(stageDef.nameOf(0)).thenReturn("陌生人");
        when(stageDef.nextThresholdOf(0)).thenReturn(100);

        RelationshipDto dto = impl.findOrCreateRelationship(1L, 1L);

        assertThat(dto.intimacyScore()).isZero();
        assertThat(dto.currentStageName()).isEqualTo("陌生人");
        assertThat(dto.nextStageThreshold()).isEqualTo(100);
    }

    @Test
    void fetchMyRelationship_should_delegate() {
        RelationshipDto expected = new RelationshipDto(1L, 1L, 50, 0, "陌生人", 100, 0.5);
        when(fetchService.fetchMyRelationship(1L, 1L)).thenReturn(expected);

        assertThat(impl.fetchMyRelationship(1L, 1L)).isSameAs(expected);
    }

    @Test
    void getStagePromptSegment_should_findOrCreate_then_call_override_service() {
        RelationshipEntity rel = RelationshipTestFixtures.relationshipAtStage(1L, 1L, 2, 400);
        when(findOrCreate.findOrCreate(1L, 1L)).thenReturn(rel);
        when(stageOverrideQuery.buildSegment(1L, 2)).thenReturn("当前关系阶段：暧昧。称呼用户用：宝。语调：温柔。");

        assertThat(impl.getStagePromptSegment(1L, 1L)).contains("暧昧");
    }

    @Test
    void findById_should_return_null_when_absent() {
        when(charRepo.findById(99L)).thenReturn(Optional.empty());

        assertThat(impl.findById(99L)).isNull();
    }

    @Test
    void findById_should_return_dto_when_present() {
        AiCharacterEntity ch = AiCharacterTestFixtures.xiaowan();
        when(charRepo.findById(1L)).thenReturn(Optional.of(ch));

        AiCharacterDto dto = impl.findById(1L);
        assertThat(dto).isNotNull();
        assertThat(dto.name()).isEqualTo("小婉");
    }
}
