package com.sanyan.memory.internal.rag;

import com.sanyan.common.error.BusinessException;
import com.sanyan.embedding.EmbeddingApi;
import com.sanyan.memory.internal.MemoryErrCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Plan 2 Task P3：把 {@link MemoryChunkBuilder.Chunk} 列表批量向量化并落库到 {@code chat_embeddings}。
 *
 * <p>调用链上的位置：
 * <pre>
 *     消息累计达阈值 → MemoryChunkBuilder.build(messages) → List&lt;Chunk&gt;
 *                                                            ↓
 *                                                    本 service：批量 embed + 入库
 *                                                            ↓
 *                                          后续 Phase P4 MemoryRagSearchService 拿来做 ANN 检索
 * </pre>
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>单次批量 HTTP</b>：N 个 chunks 必须在一次 {@link EmbeddingApi#embed} 调用里
 *       一起送出去——远程 BGE-M3 服务支持批量，省 N-1 次 HTTP 往返开销。绝不允许在循环里
 *       一条条调 provider。</li>
 *   <li><b>向量数 ≠ chunks 数 → 抛异常</b>：协议层面应保证一一对应，不一致只能视作远端
 *       异常。直接 {@link MemoryErrCode#EMBEDDING_SERVICE_UNAVAILABLE}，由 P5 调用方决定
 *       是否重试 / 进 DLQ。</li>
 *   <li><b>不在本层降级</b>：provider 抛 {@link BusinessException} 时原样重新抛。是否降级
 *       （比如吞掉异常、本批跳过、写 DLQ）是<b>调用方</b>的业务决策，本服务只负责"成功
 *       入库或失败暴露"。Spec §4.1 明确要求 RAG 检索层降级，索引层不降级。</li>
 *   <li><b>空 / null 早退</b>：避免对空批次发出无意义 HTTP 调用。</li>
 * </ul>
 *
 * <p>事务边界：本 service 不显式标 {@code @Transactional}。{@code repository.saveAll}
 * 在 JPA 默认事务范围内即可保证批量插入的原子性；调用方（P5 listener）需自行根据业务
 * 决定整体事务边界。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MemoryEmbeddingService {

    private final EmbeddingApi embeddingApi;
    private final ChatEmbeddingRepository repository;

    /**
     * 批量索引 chunks 到 {@code chat_embeddings} 表。
     *
     * @param userId      用户 id（chunks 必须同属此 user，本 service 不再校验）
     * @param characterId 角色 id（chunks 必须同属此 character）
     * @param chunks      P2 切好的 chunk 列表；null 或空时直接 no-op
     * @throws BusinessException provider 失败或返回向量数与 chunks 数不一致
     */
    public void index(Long userId, Long characterId, List<MemoryChunkBuilder.Chunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        List<String> texts = chunks.stream()
                .map(MemoryChunkBuilder.Chunk::chunkText)
                .toList();
        List<float[]> vectors = embeddingApi.embed(texts);
        if (vectors.size() != chunks.size()) {
            log.error("Embedding 返回向量数 {} ≠ chunks 数 {}", vectors.size(), chunks.size());
            throw new BusinessException(MemoryErrCode.EMBEDDING_SERVICE_UNAVAILABLE);
        }
        List<ChatEmbeddingEntity> entities = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            MemoryChunkBuilder.Chunk c = chunks.get(i);
            ChatEmbeddingEntity e = new ChatEmbeddingEntity();
            e.setUserId(userId);
            e.setCharacterId(characterId);
            e.setMessageIds(c.messageIds().toArray(new Long[0]));
            e.setChunkText(c.chunkText());
            e.setEmbedding(vectors.get(i));
            e.setTokenCount(c.tokenCount());
            entities.add(e);
        }
        repository.saveAll(entities);
        log.info("索引 {} 个 chunks 到 chat_embeddings (user={} char={})",
                entities.size(), userId, characterId);
    }
}
