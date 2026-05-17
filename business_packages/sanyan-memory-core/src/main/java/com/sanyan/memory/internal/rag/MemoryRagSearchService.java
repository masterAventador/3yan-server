package com.sanyan.memory.internal.rag;

import com.sanyan.common.error.BusinessException;
import com.sanyan.llm.internal.EmbeddingProvider;
import com.sanyan.llm.internal.LlmErrCode;
import com.sanyan.memory.dto.MemoryFragment;
import com.sanyan.memory.MemoryConstants;
import com.sanyan.memory.internal.MemoryErrCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Plan 2 Task P4：长期记忆「向量检索（RAG）」查询入口。
 *
 * <p>调用链上的位置：
 * <pre>
 *     用户当前消息 → MemoryRagSearchService.search → embed(query) → repo.findTopKByCosineSimilarity
 *                                                                          ↓
 *                                                              Top {@link MemoryConstants#RAG_TOP_K} 片段
 *                                                                          ↓
 *                                              MemoryContextBuilder（Q1）拼进 system prompt
 * </pre>
 *
 * <p><b>降级语义（spec §4.1，强制）：</b>远程 BGE-M3 embedding 服务不可用时（捕获
 * {@code BusinessException(EMBEDDING_SERVICE_UNAVAILABLE)}），<b>返回空 list + 打 ERROR 日志</b>，
 * 不向上抛。理由：
 * <ul>
 *   <li>RAG 是<b>锦上添花</b>能力——召回挂掉时主对话短期上下文 + summary + profile 仍能工作</li>
 *   <li>下游 {@link MemoryContextBuilder} 见到空列表跳过 RAG 段拼接，对用户完全透明</li>
 * </ul>
 *
 * <p>降级范围严格限定在 EMBEDDING_SERVICE_UNAVAILABLE：
 * <ul>
 *   <li>{@link MemoryErrCode#EMBEDDING_SERVICE_UNAVAILABLE}（code 5002）—— 本模块定义</li>
 *   <li>{@link LlmErrCode#EMBEDDING_SERVICE_UNAVAILABLE}（code 4004）—— RemoteBgeM3Provider 实际抛的
 *       是这个，跨 enum 但 code 语义一致；按 code 识别避免漏判</li>
 * </ul>
 * 其他 {@link BusinessException}（如 {@code LLM_UPSTREAM_4XX}）<b>原样上抛</b>，
 * 由 GlobalExceptionHandler 转 400 给端上。
 *
 * <p>本 service 不显式标 {@code @Transactional}：纯查询路径，无写操作。
 *
 * <p><b>S3 Phase 4 cleanup todo</b>：当前本类 import {@code com.sanyan.llm.internal.EmbeddingProvider}
 * 和 {@code com.sanyan.llm.internal.LlmErrCode}（通过 sanyan-business → sanyan-llm-core 传递依赖能编译，
 * 但违反 -api 边界）。Phase 4 拆 sanyan-embedding-api/core 时：
 * <ul>
 *   <li>{@code EmbeddingProvider} → {@code com.sanyan.embedding.EmbeddingApi}</li>
 *   <li>{@code LlmErrCode.EMBEDDING_SERVICE_UNAVAILABLE}（4004）→
 *       {@code EmbeddingErrCode.EMBEDDING_SERVICE_UNAVAILABLE}（6001），LlmErrCode 删 4004</li>
 *   <li>memory-core/pom 加 {@code sanyan-embedding-api} 依赖；删 sanyan-business 整包依赖</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MemoryRagSearchService {

    private final EmbeddingProvider embeddingProvider;
    private final ChatEmbeddingRepository repository;

    /**
     * 按当前用户消息做 RAG 召回，返回 Top K 历史片段（按相似度降序）。
     *
     * @param userId      用户 id
     * @param characterId 角色 id（同一用户对不同角色的记忆隔离）
     * @param queryText   查询文本，通常就是用户当前输入的消息
     * @return 召回片段列表（最多 {@link MemoryConstants#RAG_TOP_K} 条，
     *         相似度 &gt; {@link MemoryConstants#RAG_MIN_COS_SIM} 才进结果）；
     *         embedding 服务不可用时返回空 list（已打 ERROR 日志）
     */
    public List<MemoryFragment> search(Long userId, Long characterId, String queryText) {
        try {
            List<float[]> queryVectors = embeddingProvider.embed(List.of(queryText));
            if (queryVectors.isEmpty()) {
                // 防御：provider 输入单 query 应必返单向量，但万一返回空别越界
                return List.of();
            }
            float[] queryVec = queryVectors.get(0);
            return repository.findTopKByCosineSimilarity(
                    userId,
                    characterId,
                    queryVec,
                    MemoryConstants.RAG_TOP_K,
                    MemoryConstants.RAG_MIN_COS_SIM);
        } catch (BusinessException e) {
            if (isEmbeddingUnavailable(e)) {
                log.error("Embedding 服务不可用，RAG 降级返回空列表 user={} character={}",
                        userId, characterId, e);
                return List.of();
            }
            throw e;
        }
    }

    /**
     * 判断异常是否属于"embedding 服务不可用"，按 code 识别而不是 enum 引用——
     * {@code RemoteBgeM3Provider}（M2c task）抛的是 {@link LlmErrCode#EMBEDDING_SERVICE_UNAVAILABLE}
     * （code 4004），而 {@link MemoryEmbeddingService}（P3 task）抛的是
     * {@link MemoryErrCode#EMBEDDING_SERVICE_UNAVAILABLE}（code 5002）。两种来源都要降级。
     */
    private static boolean isEmbeddingUnavailable(BusinessException e) {
        int code = e.getErrCode().getCode();
        return code == MemoryErrCode.EMBEDDING_SERVICE_UNAVAILABLE.getCode()
                || code == LlmErrCode.EMBEDDING_SERVICE_UNAVAILABLE.getCode();
    }
}
