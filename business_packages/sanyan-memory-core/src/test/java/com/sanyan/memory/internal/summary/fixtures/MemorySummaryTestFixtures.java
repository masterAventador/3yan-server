package com.sanyan.memory.internal.summary.fixtures;

import com.sanyan.memory.internal.summary.MemorySummaryEntity;

/**
 * MemorySummaryEntity 的 Object Mother (java-backend-business-layer.md §5.2)。
 *
 * <p>测试中需要 MemorySummaryEntity 时一律通过这里构造，禁止裸 new + setter 散落各处。
 * 修字段默认值时只需改这里，所有测试用例自动跟随。
 *
 * <p>方法语义约定：
 * <ul>
 *   <li>{@link #validSummary()}：默认有效摘要（user=1 / character=1 / 30 条消息覆盖）</li>
 *   <li>{@link #summaryForUser(Long)}：指定 user 的有效摘要（用于隔离查询测试）</li>
 *   <li>{@link #summaryWithMessageCount(int)}：指定消息数的有效摘要（period_end_message_id 同步推进）</li>
 * </ul>
 */
public final class MemorySummaryTestFixtures {

    private MemorySummaryTestFixtures() {}

    public static MemorySummaryEntity validSummary() {
        MemorySummaryEntity e = new MemorySummaryEntity();
        e.setUserId(1L);
        e.setCharacterId(1L);
        e.setPeriodStartMessageId(1L);
        e.setPeriodEndMessageId(30L);
        e.setSummaryText("测试摘要文本");
        e.setMessageCount(30);
        return e;
    }

    public static MemorySummaryEntity summaryForUser(Long userId) {
        MemorySummaryEntity e = validSummary();
        e.setUserId(userId);
        return e;
    }

    public static MemorySummaryEntity summaryWithMessageCount(int count) {
        MemorySummaryEntity e = validSummary();
        e.setMessageCount(count);
        e.setPeriodEndMessageId((long) count);
        return e;
    }
}
