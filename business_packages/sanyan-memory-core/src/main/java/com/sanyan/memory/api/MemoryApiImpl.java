package com.sanyan.memory.api;

import com.sanyan.memory.MemoryApi;
import com.sanyan.memory.dto.MemoryContext;
import com.sanyan.memory.internal.orchestrator.MemoryContextBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Plan 2 Task Q2：{@link MemoryApi} 在 -core 模块的薄壳实现。
 *
 * <p>本类**只做委托**，把入参原样传给 {@link MemoryContextBuilder#build}，把返回值原样返回。
 * 三层组合的编排逻辑、空数据降级、文本拼接全部在 builder 里完成（Q1 已覆盖单测），
 * 这里不重复任何业务规则——保持 -api 实现层"薄"的 java-backend §3.4 约束。
 *
 * <p>放在 {@code api/} 子包是 java-backend rule §3.1 强制的目录结构：
 * 对内 Java 接口入口的实现必须在 {@code <domain>-core/api/} 下，与 {@code internal/} 隔离。
 */
@Service
@RequiredArgsConstructor
public class MemoryApiImpl implements MemoryApi {

    private final MemoryContextBuilder builder;

    @Override
    public MemoryContext getRelevantContext(Long userId, Long characterId, String currentUserMessage) {
        return builder.build(userId, characterId, currentUserMessage);
    }
}
