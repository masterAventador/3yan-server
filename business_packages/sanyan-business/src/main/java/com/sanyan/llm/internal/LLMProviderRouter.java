package com.sanyan.llm.internal;

import com.sanyan.chat.internal.MessageEntity;
import com.sanyan.chat.internal.SenderType;
import com.sanyan.common.error.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * LLM Provider 路由层（M3 task）。
 *
 * <p>按 {@link LLMTaskType} 在所有 {@link LLMProvider} 实现中挑出第一个匹配的：
 * <ul>
 *   <li>{@link LLMTaskType#USER_FACING} → {@link DoubaoAdapter}</li>
 *   <li>{@link LLMTaskType#BACKGROUND} → {@link DeepSeekAdapter}</li>
 * </ul>
 *
 * <p>装配方式：Spring 通过 {@code List<LLMProvider>} 收集所有 {@code @Component} 的 provider
 * 实现，按字段定义顺序构造注入。调用方（{@link AiService} / 后续 Phase N/O 的摘要档案 service）
 * 只依赖 router 这一层，不直接 import 具体 adapter。
 *
 * <p>错误语义：
 * <ul>
 *   <li>0 个 provider 匹配 → 抛 {@link BusinessException}(LLM_PROVIDER_NOT_FOUND)，意味着装配
 *       遗漏或配置缺失（生产期不应该发生）</li>
 *   <li>多个 provider 匹配 → log.warn + 取首个，行为可预期（按 Spring 注入顺序）</li>
 * </ul>
 *
 * <p>chat 入参 {@code systemPrompt} + {@code messages} 在 router 层组装成 OpenAI 兼容的
 * {@code [{role, content}, ...]} 格式后传给 provider——这样组装逻辑只写一次，所有 provider 共享。
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
     * @param taskType     任务类型，决定走哪个 provider
     * @param systemPrompt system 消息内容（人设、当前时间等）
     * @param messages     用户与 AI 的历史对话（按时间正序）
     * @return provider 返回的助手回复文本
     * @throws BusinessException 找不到匹配 provider，或 provider 上游异常向上传递
     */
    public String chat(LLMTaskType taskType, String systemPrompt, List<MessageEntity> messages) {
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
        List<Map<String, String>> chatMessages = buildOpenAiMessages(systemPrompt, messages);
        return provider.chat(chatMessages);
    }

    /**
     * 把 system prompt + Plan 1 时代的 {@link MessageEntity} 列表组装成 OpenAI 兼容的
     * {@code [{role, content}, ...]} 结构。
     *
     * <p>从原 {@code AiService.buildChatMessages} 搬迁，逻辑保持一致：USER 消息映射成
     * {@code role=user}，其余（AI）映射成 {@code role=assistant}；{@code null} content 替换为空串。
     */
    static List<Map<String, String>> buildOpenAiMessages(
            String systemPrompt, List<MessageEntity> messages) {
        List<Map<String, String>> chatMessages = new ArrayList<>();
        chatMessages.add(Map.of("role", "system", "content", systemPrompt));
        if (messages != null) {
            for (MessageEntity msg : messages) {
                String role = SenderType.USER.equals(msg.getSenderType()) ? "user" : "assistant";
                String content = msg.getContent() == null ? "" : msg.getContent();
                chatMessages.add(Map.of("role", role, "content", content));
            }
        }
        return chatMessages;
    }
}
