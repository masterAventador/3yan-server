package com.sanyan.embedding.api;

import com.sanyan.embedding.EmbeddingApi;
import com.sanyan.embedding.internal.BgeM3DjlAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * {@link EmbeddingApi} 在 embedding-server 侧的实现。
 *
 * <p>极薄壳：直接委托给 {@link BgeM3DjlAdapter}。
 * 拆这层的原因：契约 {@code EmbeddingApi}（在 -api 模块）独立于具体推理实现，
 * 未来如果换模型（如换 Cohere / Voyage）只需替换 adapter，无需动调用方（Controller）。
 *
 * <p>Spring 装配关系：{@code @Service} 让 Controller / Health Indicator 能注入 {@code EmbeddingApi}。
 */
@Service
@RequiredArgsConstructor
public class EmbeddingApiImpl implements EmbeddingApi {

    private final BgeM3DjlAdapter adapter;

    @Override
    public List<float[]> embed(List<String> texts) {
        return adapter.embed(texts);
    }
}
