package com.sanyan.character.internal.intimacy.fixtures;

import com.sanyan.character.internal.intimacy.IntimacyLogEntity;

/**
 * IntimacyLogEntity Object Mother (java-backend-business-layer.md §5.2)。
 * 测试中需要 IntimacyLogEntity 时一律通过这里构造，不允许裸 new。
 */
public final class IntimacyLogTestFixtures {

    private IntimacyLogTestFixtures() {}

    public static IntimacyLogEntity validLog(Long userId, Long characterId, String reason, int delta, int newScore, int newStage) {
        IntimacyLogEntity log = new IntimacyLogEntity();
        log.setUserId(userId);
        log.setCharacterId(characterId);
        log.setReason(reason);
        log.setDelta(delta);
        log.setNewScore(newScore);
        log.setNewStage((short) newStage);
        return log;
    }
}
