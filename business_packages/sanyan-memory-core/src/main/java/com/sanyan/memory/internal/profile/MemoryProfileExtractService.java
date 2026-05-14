package com.sanyan.memory.internal.profile;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanyan.chat.internal.MessageEntity;
import com.sanyan.common.error.BusinessException;
import com.sanyan.llm.internal.LLMProviderRouter;
import com.sanyan.llm.internal.LLMTaskType;
import com.sanyan.memory.internal.profile.dto.ProfileExtraction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Plan 2 Task O2：从用户最近 N 条消息里抽取结构化档案更新候选（{@link ProfileExtraction}）。
 *
 * <p>调用 {@link LLMProviderRouter} 并把任务类型钉死为 {@link LLMTaskType#BACKGROUND}——
 * 档案抽取属于后台异步任务，用户看不到原始 LLM 输出，路由到 DeepSeek V4-Flash（低成本 + 大吞吐）。
 *
 * <p><b>容错链路（强约束）</b>：本 service 只在「拿到合法结构化数据」时返回非 null。
 * 其余情况一律返回 {@code null} + log.warn，<b>不抛异常</b>——让上游 O4 listener 简单
 * 走 {@code if (result == null) skip} 跳过，避免异常中断整条 listener 调用链。
 *
 * <p>具体处理：
 * <ul>
 *   <li>{@code messages} 为 null / 空 → 直接 null（无内容可抽取）</li>
 *   <li>{@code llmRouter.chat} 抛 {@link BusinessException}（上游 LLM 4xx/5xx/网络异常）→ 捕获 + log.warn + 返回 null</li>
 *   <li>LLM 返回 null / blank → null</li>
 *   <li>LLM 返回非合法 JSON → log.warn + null（<b>严格解析，不尝试"修复"非法 JSON</b>，避免引入隐性 bug）</li>
 * </ul>
 *
 * <p><b>Jackson 命名策略</b>：JSON schema 字段是 snake_case，{@link ProfileExtraction} record 字段是
 * camelCase。{@link ProfileExtraction} 自身用 {@code @JsonNaming(SnakeCaseStrategy)} 局部声明命名映射——
 * 本 service 直接复用 Spring 注入的全局 {@link ObjectMapper} 即可，不需要 copy + 改命名策略。
 *
 * <p>system prompt 模板硬约束（由 {@code MemoryProfileExtractServiceTest} 守护）：JSON schema
 * 的 4 个关键字段名必须出现在 prompt 文本里，且要明确要求"只输出 JSON"。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MemoryProfileExtractService {

    /**
     * System prompt 模板。
     *
     * <p>schema 4 字段名（basic_info_updates / preferences_appended / events_appended / emotion_signal）
     * 必须保留——单测断言守护，prompt 改名要先改测试。
     */
    static final String SYSTEM_PROMPT = """
            你是一个用户档案抽取器。从用户的最近 N 条消息里抽取结构化信息，输出 JSON。
            JSON schema:
            {
              "basic_info_updates": {"occupation": "...", "age": null, "hometown": "..."},
              "preferences_appended": {"foods": ["..."], "movies": [], "colors": [], "music": []},
              "events_appended": [{"type": "...", "content": "...", "date": "..."|null}],
              "emotion_signal": "焦虑|开心|低落|平静|... 或 null"
            }
            只输出 JSON，不要任何前后缀文字。如果没有可抽取内容，所有字段返回 null/空数组。
            """;

    private final LLMProviderRouter llmRouter;
    private final ObjectMapper objectMapper;

    /**
     * 从对话片段抽取结构化档案更新候选。
     *
     * @param recentMessages 用户最近 N 条消息（O4 listener 决定 N 的大小）
     * @return 抽取结果；任何异常路径都返回 {@code null}（容错），调用方简单跳过即可
     */
    public ProfileExtraction extract(List<MessageEntity> recentMessages) {
        if (recentMessages == null || recentMessages.isEmpty()) {
            return null;
        }

        String llmOutput;
        try {
            llmOutput = llmRouter.chat(LLMTaskType.BACKGROUND, SYSTEM_PROMPT, recentMessages);
        } catch (BusinessException e) {
            log.warn("Profile 抽取 LLM 调用失败，跳过: errCode={}, msg={}",
                    e.getErrCode().getCode(), e.getMessage());
            return null;
        }

        if (llmOutput == null || llmOutput.isBlank()) {
            return null;
        }

        try {
            return objectMapper.readValue(llmOutput, ProfileExtraction.class);
        } catch (JsonProcessingException e) {
            log.warn("Profile 抽取 JSON 解析失败，跳过: output={}", llmOutput, e);
            return null;
        }
    }
}
