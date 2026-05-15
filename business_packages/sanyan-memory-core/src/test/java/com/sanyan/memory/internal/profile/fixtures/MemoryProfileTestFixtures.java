package com.sanyan.memory.internal.profile.fixtures;

import com.sanyan.memory.internal.profile.MemoryProfileEntity;

/**
 * MemoryProfileEntity 的 Object Mother (java-backend-business-layer.md §5.2)。
 *
 * <p>测试中需要 MemoryProfileEntity 时一律通过这里构造，禁止裸 new + setter 散落各处。
 * 修字段默认值时只需改这里，所有测试用例自动跟随。
 *
 * <p>Plan 2.5 重构：profile 改为 free-form summary（{@code summary_text} TEXT 列），
 * 旧的 {@code profileWithFoods} / {@code profileWithName} slot 相关 fixture 已删除。
 *
 * <p>方法语义约定：
 * <ul>
 *   <li>{@link #validProfile()}：默认有效画像（user=1 / character=1 / summaryText 为空字符串）</li>
 *   <li>{@link #profileWithSummary(String)}：指定 summaryText 的有效画像</li>
 * </ul>
 */
public final class MemoryProfileTestFixtures {

    private MemoryProfileTestFixtures() {}

    /** 默认有效画像：user=1 / character=1，summaryText 为空（对齐 V8 schema 默认值 ''）。 */
    public static MemoryProfileEntity validProfile() {
        MemoryProfileEntity e = new MemoryProfileEntity();
        e.setUserId(1L);
        e.setCharacterId(1L);
        e.setSummaryText("");
        return e;
    }

    /** 指定 summaryText 的有效画像。 */
    public static MemoryProfileEntity profileWithSummary(String summary) {
        MemoryProfileEntity e = validProfile();
        e.setSummaryText(summary);
        return e;
    }
}
