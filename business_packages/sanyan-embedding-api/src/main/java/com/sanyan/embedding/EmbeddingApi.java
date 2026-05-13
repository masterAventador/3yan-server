package com.sanyan.embedding;

import java.util.List;

/**
 * 远程 embedding 服务对内 API 契约。
 *
 * <p>主服务侧通过 HTTP 客户端实现（{@code RemoteBgeM3Provider}）调用 embedding-server；
 * embedding-server 侧由 {@code EmbeddingApiImpl} 包装 {@code BgeM3FastembedAdapter} 实现。
 *
 * <p>方法签名保持极简（{@code List<String> → List<float[]>}）：
 * {@link com.sanyan.embedding.dto.EmbedResponse} 中的 {@code dim} 与 {@code latencyMs}
 * 仅用于 HTTP 层透传与监控，对 Java 调用方不暴露——调用方拿到向量就够了。
 *
 * <p>错误语义：上游异常（网络 / 4xx / 5xx）由实现侧抛 {@code BusinessException}，本接口不约束。
 */
public interface EmbeddingApi {

    /**
     * 批量将文本转换为向量。
     *
     * @param texts 待向量化的文本列表；调用方需保证非空、每条不超过 2000 字符（详见 EmbedRequest 校验）
     * @return 向量列表，与入参顺序一一对应；维度对当前 BGE-M3 模型为 1024
     */
    List<float[]> embed(List<String> texts);
}
