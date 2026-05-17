package com.sanyan.llm;

import com.sanyan.llm.dto.ChatMessage;

import java.util.List;

/**
 * LLM 域对内 API 契约。
 *
 * <p>由 llm-core 的 ApiImpl 内部按 {@link LlmTaskType} 路由到具体 provider（豆包 / DeepSeek）。
 *
 * <p>错误语义：上游失败由 llm-core 抛 {@code BusinessException}（详见 ERROR_CODE_REGISTRY.md 的
 * 4xxx 区间），调用方按需 catch 降级（如 RAG 检索 catch 后返回空，不污染 prompt）。
 *
 * <p>embedding 不在本接口范围；用 {@code EmbeddingApi}（sanyan-embedding-api，Phase 4 引入）。
 */
public interface LlmApi {
    /**
     * 调 LLM 进行对话推理。
     *
     * @param taskType 决定 provider（USER_FACING 走豆包，BACKGROUND 走 DeepSeek）
     * @param messages OpenAI 兼容 chat messages（system + user + assistant 序列）
     * @return AI 回复的纯文本
     */
    String chat(LlmTaskType taskType, List<ChatMessage> messages);
}
