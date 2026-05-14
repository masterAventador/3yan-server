package com.sanyan.memory.internal.profile.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;
import java.util.Map;

/**
 * Plan 2 Task O2：LLM 抽取出来的「档案更新候选」结构化结果（内部 DTO）。
 *
 * <p>对应 system prompt 中要求的 JSON schema：
 * <pre>
 * {
 *   "basic_info_updates": {"occupation": "...", "age": null, "hometown": "..."},
 *   "preferences_appended": {"foods": ["..."], "movies": [], "colors": [], "music": []},
 *   "events_appended": [{"type": "...", "content": "...", "date": "..."|null}],
 *   "emotion_signal": "焦虑|开心|低落|平静|... 或 null"
 * }
 * </pre>
 *
 * <p>本 record 是<b>内部 DTO</b>，位于 {@code internal/profile/dto}，不暴露到 {@code -api} 模块——
 * 它只在 O2 ExtractService（生产）和 O3 MergeService（消费）之间流转，不算跨模块契约。
 *
 * <p><b>命名策略</b>：JSON 字段是 snake_case（{@code basic_info_updates}），record 组件是
 * camelCase（{@code basicInfoUpdates}）。用类级 {@link JsonNaming} 把 Jackson 默认的
 * camelCase 反序列化策略覆盖为 {@link PropertyNamingStrategies.SnakeCaseStrategy} —— 作用域
 * <b>仅限本 record</b>，不污染全局 ObjectMapper 的其他用法（如 HTTP 响应序列化）。
 *
 * <p>所有字段允许为 {@code null}（LLM 可能只输出部分字段），值类型保持 weakly-typed（{@code Map<String, Object>}
 * 等），原因和 Entity 一致：profile 字段结构演进频繁，强类型校验交给 O3 MergeService 业务层。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ProfileExtraction(
        Map<String, Object> basicInfoUpdates,
        Map<String, List<String>> preferencesAppended,
        List<Map<String, Object>> eventsAppended,
        String emotionSignal
) {
}
