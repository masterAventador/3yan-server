package com.sanyan.memory.internal.item;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * LLM 记忆抽取返回的 JSON 解析结果（spec §4.2 / §4.3）。
 *
 * <p>LLM 对最近消息 + 现有 PENDING 条目判定后，逐条给出 action（NEW / UPDATE / SKIP）。
 * {@code MemoryItemExtractService} 拿到后按 action 落库新建 / 更新 / 跳过。
 *
 * <p>容错：LLM 可能用 ```json 代码块包裹、可能输出非 JSON 噪音；{@link #parse} 一律降级为
 * 空 items（不抛异常），保证抽取链路即使 LLM 抽风也不影响主对话（抽取是后台异步任务）。
 *
 * <p>沿用项目既有 Jackson 模式（无 JsonUtil；见 {@code RagIndexWorker}）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MemoryItemExtractResult(List<Item> items) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 单条抽取项。
     *
     * @param action   NEW / UPDATE / SKIP
     * @param kind     PLAN_EVENT / EMOTION / PROMISE（大写 enum name）
     * @param content  条目内容（SKIP 时可空）
     * @param dateHint 事件日期提示（ISO 日期串，如 "2026-06-03"；无则 null）
     * @param targetId UPDATE / SKIP 时指向的已有条目 id；NEW 时 null
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(String action, String kind, String content, String dateHint, Long targetId) {}

    /** items 为 null 时归一化为空列表，避免下游空指针。 */
    public MemoryItemExtractResult {
        items = items == null ? List.of() : items;
    }

    /**
     * 解析 LLM 返回文本为抽取结果。容错：blank / 非 JSON / 缺字段一律降级为空 items。
     *
     * @param raw LLM 原始返回（可能含 markdown 代码块围栏）
     * @return 解析结果；解析失败时 items 为空列表
     */
    public static MemoryItemExtractResult parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new MemoryItemExtractResult(List.of());
        }
        String cleaned = stripCodeFence(raw);
        try {
            return MAPPER.readValue(cleaned, MemoryItemExtractResult.class);
        } catch (Exception e) {
            return new MemoryItemExtractResult(List.of());
        }
    }

    /** 剥掉 LLM 常见的 ```json ... ``` 围栏，只保留中间内容。 */
    private static String stripCodeFence(String raw) {
        String s = raw.trim();
        if (s.startsWith("```")) {
            int firstNl = s.indexOf('\n');
            if (firstNl >= 0) {
                s = s.substring(firstNl + 1);
            }
            if (s.endsWith("```")) {
                s = s.substring(0, s.length() - 3);
            }
        }
        return s.trim();
    }
}
