package com.sanyan.character.internal.intimacy;

public record IntimacyEvent(Type type, Long userId, Long characterId, int payloadInt, String payloadStr) {
    public enum Type { MESSAGE_SENT, DAILY_LOGIN, PLOT_MILESTONE, AI_QUALITY_BONUS }

    public static IntimacyEvent messageSent(Long uid, Long cid) {
        return new IntimacyEvent(Type.MESSAGE_SENT, uid, cid, 0, null);
    }

    public static IntimacyEvent dailyLogin(Long uid, Long cid, int streak) {
        return new IntimacyEvent(Type.DAILY_LOGIN, uid, cid, streak, null);
    }

    public static IntimacyEvent plot(Long uid, Long cid, String ruleId, int delta) {
        return new IntimacyEvent(Type.PLOT_MILESTONE, uid, cid, delta, ruleId);
    }

    public static IntimacyEvent aiQuality(Long uid, Long cid, int score) {
        return new IntimacyEvent(Type.AI_QUALITY_BONUS, uid, cid, score, null);
    }
}
