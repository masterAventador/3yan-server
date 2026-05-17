package com.sanyan.embedding.api;

import com.sanyan.embedding.EmbeddingApi;
import com.sanyan.embedding.internal.SiliconFlowEmbeddingProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * EmbeddingApi 实现：薄壳，委托给 SiliconFlowEmbeddingProvider。
 *
 * <p>所有 retry / 错误码 / HTTP 细节都封装在 SiliconFlowEmbeddingProvider 内。
 * 未来如果换 provider（例如 Qwen / OpenAI embeddings），只动 internal，不动 -api 契约。
 */
@Service
@RequiredArgsConstructor
public class EmbeddingApiImpl implements EmbeddingApi {

    private final SiliconFlowEmbeddingProvider provider;

    @Override
    public List<float[]> embed(List<String> texts) {
        return provider.embed(texts);
    }
}
