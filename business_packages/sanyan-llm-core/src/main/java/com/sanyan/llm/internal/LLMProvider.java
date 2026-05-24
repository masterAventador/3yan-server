package com.sanyan.llm.internal;

import com.sanyan.llm.LlmTaskType;

import java.util.List;
import java.util.Map;

/**
 * LLM Provider 抽象——所有具体 LLM 适配器实现此接口。
 *
 * <p>路由层（{@link LLMProviderRouter}）按 {@link LlmTaskType} 在所有 {@code LLMProvider}
 * 实现中挑出 {@link #supports(LlmTaskType)} 返回 true 的；每个实现通过配置（如 application.yml
 * 的 {@code sanyan.<provider>.task-types}）声明自己能接哪些 task type，路由不再硬编码绑定。
 *
 * <p>chat 消息使用 OpenAI 兼容的 {@code [{role, content}, ...]} 数组结构，与上游 LLM 协议同构，
 * 避免在 Provider 层重新建模。
 */
public interface LLMProvider {

    /**
     * 调用 LLM 生成回复。
     *
     * @param chatMessages OpenAI 兼容格式的消息列表，每条 Map 至少含 {@code "role"} 和 {@code "content"}。
     * @return LLM 返回的助手文本。
     * @throws com.sanyan.common.error.BusinessException 上游 4xx / 5xx / 网络异常等。
     */
    String chat(List<Map<String, String>> chatMessages);

    /**
     * 当前 Provider 是否能处理给定的任务类型。Router 据此选择 Provider。
     */
    boolean supports(LlmTaskType taskType);

    /**
     * 当前 Provider 使用的模型名（用于日志 / 监控 / 路由调试）。
     */
    String model();
}
