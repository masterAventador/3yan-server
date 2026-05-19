package com.sanyan.character.internal.fixtures;

import com.sanyan.character.internal.RelationshipEntity;

/**
 * RelationshipEntity Object Mother (java-backend-business-layer.md §5.2)。
 * 测试中需要 RelationshipEntity 时一律通过这里构造，不允许裸 new。
 */
public final class RelationshipTestFixtures {

    private RelationshipTestFixtures() {}

    public static RelationshipEntity validRelationship(Long userId, Long characterId) {
        RelationshipEntity r = new RelationshipEntity();
        r.setUserId(userId);
        r.setCharacterId(characterId);
        return r;
    }

    public static RelationshipEntity relationshipWithScore(Long userId, Long characterId, int score) {
        RelationshipEntity r = validRelationship(userId, characterId);
        r.setIntimacyScore(score);
        return r;
    }

    public static RelationshipEntity relationshipAtStage(Long userId, Long characterId, int stage, int score) {
        RelationshipEntity r = relationshipWithScore(userId, characterId, score);
        r.setCurrentStage((short) stage);
        return r;
    }
}
