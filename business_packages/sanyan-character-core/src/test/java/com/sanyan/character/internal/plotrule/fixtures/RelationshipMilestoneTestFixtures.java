package com.sanyan.character.internal.plotrule.fixtures;

import com.sanyan.character.internal.plotrule.RelationshipMilestoneEntity;

/**
 * RelationshipMilestoneEntity Object Mother (java-backend-business-layer.md §5.2)。
 * 测试中需要 RelationshipMilestoneEntity 时一律通过这里构造，不允许裸 new。
 */
public final class RelationshipMilestoneTestFixtures {

    private RelationshipMilestoneTestFixtures() {}

    public static RelationshipMilestoneEntity validMilestone(Long userId, Long characterId, String ruleId) {
        RelationshipMilestoneEntity m = new RelationshipMilestoneEntity();
        m.setUserId(userId);
        m.setCharacterId(characterId);
        m.setRuleId(ruleId);
        return m;
    }
}
