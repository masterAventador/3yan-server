package com.sanyan.llm.api;

import com.sanyan.llm.LlmApi;
import com.sanyan.llm.LlmTaskType;
import com.sanyan.llm.dto.ChatMessage;
import com.sanyan.llm.internal.LLMProviderRouter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * {@link LlmApi} 实现：把契约层的 {@code List<ChatMessage>} 转成内部 router 期望的
 * {@code List<Map<role,content>>}，再委托 {@link LLMProviderRouter} 按 taskType 路由 provider。
 *
 * <p>本类故意保持薄：所有 routing / retry / 错误码 都封装在 {@link LLMProviderRouter} 和具体
 * Adapter 内。后续如果换 router 实现（比如加 streaming 支持），只动 internal，不动 -api 契约。
 *
 * <p>S3 Phase 3 引入：替代旧的"调用方直接 @Autowired LLMProviderRouter"模式，把 router 收回
 * llm-core 内部，跨模块（chat / memory 等）只看到 {@link LlmApi} 契约。
 */
@Service
@RequiredArgsConstructor
public class LlmApiImpl implements LlmApi {

    private final LLMProviderRouter router;

    @Override
    public String chat(LlmTaskType taskType, List<ChatMessage> messages) {
        List<Map<String, String>> raw = messages.stream()
                .map(m -> Map.of("role", m.role(), "content", m.content()))
                .toList();
        return router.chat(taskType, raw);
    }
}
