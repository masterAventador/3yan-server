package com.sanyan.memory.internal.profile.fixtures;

import com.sanyan.memory.internal.profile.MemoryProfileEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MemoryProfileEntity 的 Object Mother (java-backend-business-layer.md §5.2)。
 *
 * <p>测试中需要 MemoryProfileEntity 时一律通过这里构造，禁止裸 new + setter 散落各处。
 * 修字段默认值时只需改这里，所有测试用例自动跟随。
 *
 * <p>方法语义约定：
 * <ul>
 *   <li>{@link #validProfile()}：默认有效档案（user=1 / character=1 / 填充完整 JSONB schema）</li>
 *   <li>{@link #profileWithFoods(List)}：指定 preferences.foods 数组</li>
 *   <li>{@link #profileWithName(String)}：指定 basic_info.name</li>
 * </ul>
 *
 * <p>JSONB 默认结构对齐 plan 2 与 V6__init_memory_profiles.sql 头注释：
 * <pre>
 * {
 *   "basic_info":   { "name": null, "age": null, "occupation": null, "hometown": null },
 *   "preferences":  { "foods": [], "movies": [], "colors": [], "music": [] },
 *   "events":       [],
 *   "emotion_line": []
 * }
 * </pre>
 */
public final class MemoryProfileTestFixtures {

    private MemoryProfileTestFixtures() {}

    /** 默认有效档案：user=1 / character=1，JSONB 填充完整空 schema。 */
    public static MemoryProfileEntity validProfile() {
        MemoryProfileEntity e = new MemoryProfileEntity();
        e.setUserId(1L);
        e.setCharacterId(1L);
        e.setProfileJsonb(defaultEmptyJsonb());
        return e;
    }

    /** 指定 preferences.foods 数组的有效档案（其他 JSONB 子结构保持默认空）。 */
    public static MemoryProfileEntity profileWithFoods(List<String> foods) {
        MemoryProfileEntity e = validProfile();
        Map<String, Object> jsonb = deepCopyJsonb(e.getProfileJsonb());
        @SuppressWarnings("unchecked")
        Map<String, Object> preferences = (Map<String, Object>) jsonb.get("preferences");
        preferences.put("foods", new ArrayList<>(foods));
        e.setProfileJsonb(jsonb);
        return e;
    }

    /** 指定 basic_info.name 的有效档案（其他 JSONB 子结构保持默认空）。 */
    public static MemoryProfileEntity profileWithName(String name) {
        MemoryProfileEntity e = validProfile();
        Map<String, Object> jsonb = deepCopyJsonb(e.getProfileJsonb());
        @SuppressWarnings("unchecked")
        Map<String, Object> basicInfo = (Map<String, Object>) jsonb.get("basic_info");
        basicInfo.put("name", name);
        e.setProfileJsonb(jsonb);
        return e;
    }

    /** 构造默认空 JSONB schema（plan 2 / V6 头注释对齐）。 */
    public static Map<String, Object> defaultEmptyJsonb() {
        Map<String, Object> root = new LinkedHashMap<>();

        Map<String, Object> basicInfo = new LinkedHashMap<>();
        basicInfo.put("name", null);
        basicInfo.put("age", null);
        basicInfo.put("occupation", null);
        basicInfo.put("hometown", null);
        root.put("basic_info", basicInfo);

        Map<String, Object> preferences = new LinkedHashMap<>();
        preferences.put("foods", new ArrayList<>());
        preferences.put("movies", new ArrayList<>());
        preferences.put("colors", new ArrayList<>());
        preferences.put("music", new ArrayList<>());
        root.put("preferences", preferences);

        root.put("events", new ArrayList<>());
        root.put("emotion_line", new ArrayList<>());
        return root;
    }

    /** 深拷贝 JSONB（防止 Object Mother 返回的对象被测试用例共享内部 list/map）。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopyJsonb(Map<String, Object> src) {
        Map<String, Object> dst = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : src.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> m) {
                dst.put(entry.getKey(), deepCopyJsonb((Map<String, Object>) m));
            } else if (value instanceof List<?> l) {
                dst.put(entry.getKey(), new ArrayList<>(l));
            } else {
                dst.put(entry.getKey(), value);
            }
        }
        return dst;
    }
}
