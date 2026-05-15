package com.sanyan.llm.internal;

import com.sanyan.common.error.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * LLM Provider 路由层（M3 task；Q3 task 进一步收窄职责）。
 *
 * <p>按 {@link LLMTaskType} 在所有 {@link LLMProvider} 实现中挑出第一个匹配的：
 * <ul>
 *   <li>{@link LLMTaskType#USER_FACING} → {@link DoubaoAdapter}</li>
 *   <li>{@link LLMTaskType#BACKGROUND} → {@link DeepSeekAdapter}</li>
 * </ul>
 *
 * <p>装配方式：Spring 通过 {@code List<LLMProvider>} 收集所有 {@code @Component} 的 provider
 * 实现，按字段定义顺序构造注入。调用方（{@link AiService} / N2/O2 的后台 service）只依赖
 * router 这一层，不直接 import 具体 adapter。
 *
 * <p>错误语义：
 * <ul>
 *   <li>0 个 provider 匹配 → 抛 {@link BusinessException}(LLM_PROVIDER_NOT_FOUND)，意味着装配
 *       遗漏或配置缺失（生产期不应该发生）</li>
 *   <li>多个 provider 匹配 → log.warn + 取首个，行为可预期（按 Spring 注入顺序）</li>
 * </ul>
 *
 * <p><b>Q3 task 重构：</b>原 {@code buildOpenAiMessages} 拼装逻辑搬到 {@link PromptBuilder}。
 * router 退化为纯路由层，只接受调用方已经拼好的 OpenAI 兼容消息数组，不再做任何加工。
 * 这样 PromptBuilder 成为唯一的"消息拼装入口"，所有调用方（AiService / MemorySummaryService /
 * MemoryProfileRefreshService）共用拼装逻辑（值复用 + 逻辑复用）。
 */
@Slf4j
@Component
public class LLMProviderRouter {

    private final List<LLMProvider> providers;

    public LLMProviderRouter(List<LLMProvider> providers) {
        this.providers = providers;
    }

    /**
     * 路由到匹配的 provider 并发起一次 chat 调用。
     *
     * @param taskType       任务类型，决定走哪个 provider
     * @param openAiMessages 已经由 {@link PromptBuilder} 拼好的 OpenAI 兼容消息数组
     * @return provider 返回的助手回复文本
     * @throws BusinessException 找不到匹配 provider，或 provider 上游异常向上传递
     */
    public String chat(LLMTaskType taskType, List<Map<String, String>> openAiMessages) {
        List<LLMProvider> matched = providers.stream()
                .filter(p -> p.supports(taskType))
                .toList();

        if (matched.isEmpty()) {
            log.error("No LLM provider supports task type {}", taskType);
            throw new BusinessException(LlmErrCode.LLM_PROVIDER_NOT_FOUND);
        }
        if (matched.size() > 1) {
            log.warn("Multiple LLM providers support task type {}: {}, picking first ({})",
                    taskType,
                    matched.stream().map(p -> p.getClass().getSimpleName()).toList(),
                    matched.get(0).getClass().getSimpleName());
        }

        LLMProvider provider = matched.get(0);
        return provider.chat(openAiMessages);
    }
}
