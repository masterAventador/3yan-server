package com.sanyan.memory.dto;

import java.time.Instant;

/**
 * 长期记忆「向量检索（RAG）」召回的单个片段（Plan 2 Task P4）。
 *
 * <p>由 {@code MemoryRagSearchService.search} 返回，下游 {@code MemoryContextBuilder}
 * 把多条 fragment 拼成"她记得你们聊过的话题"段，注入到主对话的 system prompt。
 *
 * <p>放在 -api 模块的原因：跨模块 DTO 契约。{@code sanyan-memory-core} 实现 RAG，
 * 调用方（未来的 chat / web 层）只依赖 -api 拿到结果即可，不需要触及 -core 实现。
 *
 * <p>字段语义：
 * <ul>
 *   <li>{@code chunkText}：本片段的原始对话文本（{@code chat_embeddings.chunk_text} 列），
 *       通常是若干轮 user/ai 对话拼起来的一段，便于直接拼回 prompt</li>
 *   <li>{@code occurredAt}：本片段索引时的时间戳（{@code chat_embeddings.created_at} 列），
 *       让 prompt 上下文能体现"这段记忆有多久了"</li>
 *   <li>{@code cosineSimilarity}：本片段相对于查询向量的 cosine 相似度，值域 [-1, 1]，
 *       由 pgvector 的 cosine distance 操作符 {@code <=>} 转换得来：
 *       {@code cos_sim = 1 - distance / 2}（pgvector 的 {@code <=>} 返回 0~2）。
 *       供调试 / 日志用，运行时不会再次过滤（已在 SQL WHERE 里过滤过低相似度）</li>
 * </ul>
 */
public record MemoryFragment(
        String chunkText,
        Instant occurredAt,
        double cosineSimilarity
) {}
