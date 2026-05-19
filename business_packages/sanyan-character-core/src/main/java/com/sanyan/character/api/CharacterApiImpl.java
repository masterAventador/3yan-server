package com.sanyan.character.api;

import com.sanyan.character.CharacterApi;
import com.sanyan.character.dto.AiCharacterDto;
import com.sanyan.character.dto.RelationshipDto;
import com.sanyan.character.internal.AiCharacterEntity;
import com.sanyan.character.internal.AiCharacterRepository;
import com.sanyan.character.internal.CharacterErrCode;
import com.sanyan.character.internal.RelationshipEntity;
import com.sanyan.character.internal.RelationshipFetchService;
import com.sanyan.character.internal.RelationshipFindOrCreateService;
import com.sanyan.character.internal.stage.StageDefinition;
import com.sanyan.character.internal.stage.StageOverrideQueryService;
import com.sanyan.common.error.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CharacterApiImpl implements CharacterApi {

    private final AiCharacterRepository charRepo;
    private final RelationshipFindOrCreateService findOrCreateService;
    private final RelationshipFetchService fetchService;
    private final StageOverrideQueryService stageOverrideQuery;
    private final StageDefinition stageDef;

    @Override
    public AiCharacterDto findById(Long characterId) {
        return charRepo.findById(characterId)
                .map(CharacterApiImpl::toDto)
                .orElse(null);
    }

    @Override
    public AiCharacterDto getById(Long characterId) {
        return charRepo.findById(characterId)
                .map(CharacterApiImpl::toDto)
                .orElseThrow(() -> new BusinessException(CharacterErrCode.CHARACTER_NOT_FOUND));
    }

    @Override
    public RelationshipDto findOrCreateRelationship(Long userId, Long characterId) {
        RelationshipEntity rel = findOrCreateService.findOrCreate(userId, characterId);
        return toRelationshipDto(userId, characterId, rel);
    }

    @Override
    public RelationshipDto fetchMyRelationship(Long userId, Long characterId) {
        return fetchService.fetchMyRelationship(userId, characterId);
    }

    @Override
    public String getStagePromptSegment(Long userId, Long characterId) {
        RelationshipEntity rel = findOrCreateService.findOrCreate(userId, characterId);
        return stageOverrideQuery.buildSegment(characterId, rel.getCurrentStage());
    }

    // 注：AiCharacterEntity 的字段是 avatar；DTO 上对应位置是 avatarUrl（命名解耦：
    // 存储层 column 命名 vs 跨模块契约命名独立）
    private static AiCharacterDto toDto(AiCharacterEntity e) {
        return new AiCharacterDto(e.getId(), e.getName(), e.getAvatar());
    }

    private RelationshipDto toRelationshipDto(Long userId, Long characterId, RelationshipEntity rel) {
        int stage = rel.getCurrentStage();
        int nextThreshold = stageDef.nextThresholdOf(stage);
        int prevThreshold = stage == 0 ? 0 : stageDef.nextThresholdOf(stage - 1);
        double percent;
        if (nextThreshold == Integer.MAX_VALUE) {
            percent = 1.0;
        } else {
            int range = nextThreshold - prevThreshold;
            percent = range == 0 ? 0.0 : (double) (rel.getIntimacyScore() - prevThreshold) / range;
            percent = Math.max(0.0, Math.min(1.0, percent));
        }
        return new RelationshipDto(
                userId, characterId, rel.getIntimacyScore(), stage,
                stageDef.nameOf(stage), nextThreshold, percent);
    }
}
