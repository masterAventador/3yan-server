/**
 * sanyan-embedding-api：Embedding 域对内契约（java-backend §2.2）。
 *
 * <p>只含 EmbeddingApi 单接口。零依赖。实现在 sanyan-embedding-core，
 * 通过 EmbeddingApiImpl 暴露，内部委托 SiliconFlowEmbeddingProvider（硅基流动 API 客户端）。
 *
 * <p>跟 sanyan-llm-api 并列两个独立域：llm-api 管推理（chat），embedding-api 管向量化。
 * 概念不同，按 §2.2「一个 -api 一个主接口」拆开。
 */
package com.sanyan.embedding;
