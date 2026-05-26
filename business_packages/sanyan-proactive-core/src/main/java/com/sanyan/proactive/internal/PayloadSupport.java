package com.sanyan.proactive.internal;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * payload 解析工具 + payload key 常量 + proactive 内部共享常量（单点定义，所有 Generator、Dispatcher 和触发器统一引用）。
 *
 * <p>key 常量消除各处硬编码字面量散落；
 * 静态工具方法 {@link #toLong} / {@link #toInt} 统一处理 Number / String / null 三种 JSON 反序列化情形；
 * {@link #toJson} 统一负责将 payload Map 序列化为 JSON 字符串（三个触发器共用，单点实现）。
 */
public final class PayloadSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── proactive 内部共享常量 ─────────────────────────────────────────────

    /**
     * 本期单角色 characterId（proactive 内部触发器共用，跨模块的 characterId=1L 散落是更大的
     * tech-debt，不在本次范围，只统一 proactive 内这些使用点）。
     */
    public static final Long CHARACTER_ID = 1L;

    // ── payload key 常量 ───────────────────────────────────────────────────

    /** 事件追问 / 情绪关怀使用：关联 memory_item 的 id。 */
    public static final String MEMORY_ITEM_ID = "memoryItemId";

    /** 失联召回使用：升级档位 0/1/2。 */
    public static final String ESCALATION_LEVEL = "escalationLevel";

    /** 早晚安问候使用：时段标识 "morning" / "night"。 */
    public static final String TIME_OF_DAY = "timeOfDay";

    // ── 工具方法 ───────────────────────────────────────────────────────────

    private PayloadSupport() {}

    /**
     * 将 payload Map 序列化为 JSON 字符串。
     * 三个触发器（GreetingDailyTrigger / RecallTrigger / MemoryItemScheduledListener）统一调用此方法，
     * 消除各自重复的私有 writePayload + ObjectMapper。
     * 序列化失败时返回 {@code "{}"}（静默降级，不阻断触发器主流程）。
     */
    public static String toJson(Map<String, Object> payload) {
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * 将 payload value 转为 {@code long}，兼容三种情形：
     * <ul>
     *   <li>{@code Number}（Jackson 默认把整数反序列化成 Integer/Long）</li>
     *   <li>{@code String}（前端以字符串传递 id 时）</li>
     *   <li>{@code null} → 返回 {@code 0L}</li>
     * </ul>
     */
    public static long toLong(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        return o == null ? 0L : Long.parseLong(o.toString());
    }

    /**
     * 将 payload value 转为 {@code int}，兼容 Number / String / null，
     * 非法字符串（不能解析为整数）返回 {@code 0}。
     */
    public static int toInt(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return o == null ? 0 : Integer.parseInt(o.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
