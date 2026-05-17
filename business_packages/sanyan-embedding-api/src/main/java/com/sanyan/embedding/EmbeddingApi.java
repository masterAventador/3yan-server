package com.sanyan.embedding;

import java.util.List;

/**
 * Embedding 域对内 API 契约。
 *
 * <p>实现委托给硅基流动（SiliconFlow）BAAI/bge-m3 模型（1024 维向量空间）。
 * 调用方拿到 {@code List<float[]>} 后自行入库或检索。
 *
 * <p>历史：S3 Phase 4 之前，embedding 接口叫 {@code EmbeddingProvider}，与 LLM 推理一起塞在
 * {@code sanyan.llm} 包里。Phase 4 拆出独立域，新接口 EmbeddingApi 取代 EmbeddingProvider；
 * 后者将在 Phase 4 完成时删除。
 *
 * <p>错误语义：所有协议级失败（硅基流动 API 4xx 不重试 / 5xx 重试耗尽 / 网络异常）
 * 抛 {@code BusinessException(EmbeddingErrCode.EMBEDDING_SERVICE_UNAVAILABLE)}（6001）。
 * 调用方按业务需要决定降级（如 {@code MemoryRagSearchService} catch 后返回空 list 不污染 prompt）。
 */
public interface EmbeddingApi {
    /**
     * 把文本批量转向量。
     *
     * @param texts 文本列表，每条非空
     * @return 与入参一一对应的 1024 维向量
     * @throws com.sanyan.common.error.BusinessException 上游失败时
     */
    List<float[]> embed(List<String> texts);
}
