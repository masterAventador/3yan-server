package com.sanyan.llm.internal;

import java.util.List;

/**
 * Embedding 服务提供方抽象（M2c task 引入）。
 *
 * <p>当前唯一实现：{@link RemoteBgeM3Provider}（HTTP 调用部署在 old 的 BGE-M3 服务）。
 * 抽这层接口的目的是让 P3 阶段的 {@code MemoryEmbeddingService} / P4 阶段的
 * {@code MemoryRagSearchService} 调用方依赖接口而非具体实现——后续若换 embedding
 * 提供方（local fastembed / 第三方云服务等），只换 Bean 不改调用方。
 *
 * <p>错误语义：实现侧对**协议级**失败（4xx / 5xx / 网络异常）统一抛
 * {@code BusinessException(LlmErrCode.EMBEDDING_SERVICE_UNAVAILABLE)}。
 * 是否降级（如返回空向量绕过 RAG）由**调用方**根据业务语义决定，本接口不规定。
 */
public interface EmbeddingProvider {

    /**
     * 批量将文本转换为向量。
     *
     * @param texts 待向量化的文本列表，调用方需保证非空、每条不超过 2000 字符
     * @return 向量列表，与入参顺序一一对应；当前 BGE-M3 模型维度 = 1024
     * @throws com.sanyan.common.error.BusinessException 上游失败或重试耗尽时
     */
    List<float[]> embed(List<String> texts);
}
