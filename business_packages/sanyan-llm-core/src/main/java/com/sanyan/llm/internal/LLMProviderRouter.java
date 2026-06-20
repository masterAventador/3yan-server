package com.sanyan.llm.internal;

import com.sanyan.common.error.BusinessException;
import com.sanyan.llm.LlmTaskType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * LLM Provider 路由层（M3 task；Q3 task 进一步收窄职责）。
 *
 * <p>按 {@link LlmTaskType} 在所有 {@link LLMProvider} 实现中挑出 {@code supports()} 返回 true 的。
 * 每个 provider 通过配置（如 application.yml 的 {@code sanyan.<provider>.task-types}）声明自己能接
 * 哪些 task type，具体路由由配置决定，不再硬编码绑定。
 *
 * <p>装配方式：Spring 通过 {@code List<LLMProvider>} 收集所有 {@code @Component} 的 provider
 * 实现。调用方（{@code AiService} / N2/O2 的后台 service）只依赖 router 这一层，不直接 import 具体 adapter。
 *
 * <p>错误语义：
 * <ul>
 *   <li>0 个 provider 匹配 → 抛 {@link BusinessException}(LLM_PROVIDER_NOT_FOUND)，意味着装配
 *       遗漏或配置缺失（生产期不应该发生）</li>
 *   <li>多个 provider 匹配 → 抛 {@link BusinessException}(LLM_PROVIDER_CONFLICT) fail-fast；
 *       原"warn + 取首个"行为在 classpath 顺序变化下不稳定，改成显式失败，强制 ops 修
 *       {@code task-types} 配置确保互斥</li>
 * </ul>
 *
 * <p><b>Q3 task 重构：</b>原 {@code buildOpenAiMessages} 拼装逻辑搬到 {@code PromptBuilder}。
 * router 退化为纯路由层，只接受调用方已经拼好的 OpenAI 兼容消息数组，不再做任何加工。
 * 这样 PromptBuilder 成为唯一的"消息拼装入口"，所有调用方（AiService / MemorySummaryService /
 * MemoryProfileRefreshService）共用拼装逻辑（值复用 + 逻辑复用）。
 *
 * <p><b>S3 Phase 3 重构：</b>本类从 sanyan-business 物理迁移到 sanyan-llm-core；taskType 参数从
 * 旧的 {@code LLMTaskType}（同模块 internal）改成 {@link LlmTaskType}（{@code sanyan-llm-api} 契约）。
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
     * @param openAiMessages 已经由 {@code PromptBuilder} 拼好的 OpenAI 兼容消息数组
     * @return provider 返回的助手回复文本
     * @throws BusinessException 找不到匹配 provider，或 provider 上游异常向上传递
     */
    public String chat(LlmTaskType taskType, List<Map<String, String>> openAiMessages) {
        List<LLMProvider> matched = providers.stream()
                .filter(p -> p.supports(taskType))
                .toList();

        if (matched.isEmpty()) {
            log.error("No LLM provider supports task type {}", taskType);
            throw new BusinessException(LlmErrCode.LLM_PROVIDER_NOT_FOUND);
        }
        if (matched.size() > 1) {
            log.error("LLM provider 冲突：task type {} 被多个 provider 同时支持 {}。请检查 application.yml 的 sanyan.<provider>.task-types 配置，确保 task type 互斥。",
                    taskType,
                    matched.stream().map(p -> p.getClass().getSimpleName()).toList());
            throw new BusinessException(LlmErrCode.LLM_PROVIDER_CONFLICT);
        }

        LLMProvider provider = matched.get(0);
        return provider.chat(taskType, openAiMessages);
    }
}
