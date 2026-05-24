package com.sanyan.llm.internal;

import com.sanyan.llm.LlmTaskType;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 解析 LLM provider task-types 配置字符串成 {@link LlmTaskType} 集合。
 *
 * <p>配置语法：comma-separated 列表，case-insensitive，自动 trim 空格，空 token 忽略。
 * 未知 token（不属于 {@link LlmTaskType} 任何枚举值）<b>抛 IllegalArgumentException</b>，
 * 让 yml 配错在启动期 fail-fast，而不是运行时静默路由到 0 个 provider。
 *
 * <p>由 {@link DoubaoAdapter} / {@link DeepSeekAdapter} 在 {@code @PostConstruct} 阶段
 * 调用一次，解析结果缓存到 adapter 实例字段；{@code supports()} 走 contains 查表，hot path
 * 零分配。
 */
public final class LlmTaskTypeMatcher {

    private LlmTaskTypeMatcher() {}

    /**
     * 解析配置字符串。
     *
     * @param csv comma-separated 配置值，可为 null / 空 / blank（全返回空 Set）
     * @return 解析出的 task type 集合（不可变）；空配置返回 {@link Set#of()}
     * @throws IllegalArgumentException 配置含未知 token
     */
    public static Set<LlmTaskType> parse(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(csv.split(","))
                .map(s -> s.trim().toUpperCase())
                .filter(s -> !s.isEmpty())
                .map(LlmTaskTypeMatcher::parseSingle)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static LlmTaskType parseSingle(String token) {
        try {
            return LlmTaskType.valueOf(token);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown LlmTaskType in task-types config: '" + token + "'. Valid values: "
                            + Arrays.toString(LlmTaskType.values()));
        }
    }
}
