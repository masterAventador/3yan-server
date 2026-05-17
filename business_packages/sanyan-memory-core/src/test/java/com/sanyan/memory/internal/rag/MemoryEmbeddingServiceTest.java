package com.sanyan.memory.internal.rag;

import com.sanyan.common.error.BusinessException;
import com.sanyan.llm.internal.EmbeddingProvider;
import com.sanyan.memory.internal.MemoryErrCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Plan 2 Task P3：MemoryEmbeddingService 单元测试。
 *
 * <p>纯 Mockito 单测，验证：
 * <ul>
 *   <li>单个 chunk → 单次 batch embed → 保存 1 个 entity，字段映射正确</li>
 *   <li>多个 chunks → 单次 batch embed（一次性把所有 chunk 文本送过去，省 HTTP 开销）→ 保存 N 个 entities</li>
 *   <li>空 / null chunk 列表 → 不调 provider，也不调 repository（早退避免无效 HTTP 调用）</li>
 *   <li>provider 抛 BusinessException → 原样重新抛（不在本层做降级，降级由 P5/P4 调用方决定）</li>
 *   <li>provider 返回的向量数 ≠ chunks 数 → BusinessException(EMBEDDING_SERVICE_UNAVAILABLE)
 *       （远端协议应保证一一对应，不一致视为服务异常）</li>
 *   <li>entity 字段（user_id / character_id / message_ids / chunk_text / embedding / token_count）
 *       严格按 chunk 顺序逐一映射</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class MemoryEmbeddingServiceTest {

    @Mock
    private EmbeddingProvider embeddingProvider;

    @Mock
    private ChatEmbeddingRepository repository;

    @InjectMocks
    private MemoryEmbeddingService service;

    @Test
    void index_shouldEmbedAndPersistSingleChunk() {
        Long userId = 100L;
        Long characterId = 7L;
        MemoryChunkBuilder.Chunk chunk = new MemoryChunkBuilder.Chunk(
                List.of(1L, 2L, 3L, 4L, 5L),
                "user: 消息1\nai: 回复1",
                42
        );
        float[] vector = new float[]{0.1f, 0.2f, 0.3f};
        when(embeddingProvider.embed(anyList())).thenReturn(List.of(vector));

        service.index(userId, characterId, List.of(chunk));

        // provider 收到的文本必须就是 chunk.chunkText()
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> textsCaptor = ArgumentCaptor.forClass(List.class);
        verify(embeddingProvider).embed(textsCaptor.capture());
        assertThat(textsCaptor.getValue())
                .as("应原样把 chunk.chunkText 传给 provider")
                .containsExactly("user: 消息1\nai: 回复1");

        // repository 收到的 entity 列表必须只含 1 条，字段严格映射
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatEmbeddingEntity>> entitiesCaptor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(entitiesCaptor.capture());
        List<ChatEmbeddingEntity> saved = entitiesCaptor.getValue();
        assertThat(saved).hasSize(1);
        ChatEmbeddingEntity e = saved.get(0);
        assertThat(e.getUserId()).isEqualTo(userId);
        assertThat(e.getCharacterId()).isEqualTo(characterId);
        assertThat(e.getMessageIds()).containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(e.getChunkText()).isEqualTo("user: 消息1\nai: 回复1");
        assertThat(e.getEmbedding()).isSameAs(vector);
        assertThat(e.getTokenCount()).isEqualTo(42);
    }

    @Test
    void index_shouldBatchEmbedAllChunksInOneCall() {
        Long userId = 100L;
        Long characterId = 7L;
        MemoryChunkBuilder.Chunk c1 = new MemoryChunkBuilder.Chunk(
                List.of(1L, 2L, 3L, 4L, 5L), "chunk-1 文本", 30);
        MemoryChunkBuilder.Chunk c2 = new MemoryChunkBuilder.Chunk(
                List.of(6L, 7L, 8L, 9L, 10L), "chunk-2 文本", 35);
        MemoryChunkBuilder.Chunk c3 = new MemoryChunkBuilder.Chunk(
                List.of(11L, 12L, 13L, 14L, 15L), "chunk-3 文本", 40);
        float[] v1 = new float[]{1f, 0f};
        float[] v2 = new float[]{0f, 1f};
        float[] v3 = new float[]{0.5f, 0.5f};
        when(embeddingProvider.embed(anyList())).thenReturn(List.of(v1, v2, v3));

        service.index(userId, characterId, List.of(c1, c2, c3));

        // provider 必须只调一次（单次 HTTP 批量），不是 3 次
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> textsCaptor = ArgumentCaptor.forClass(List.class);
        verify(embeddingProvider).embed(textsCaptor.capture());
        assertThat(textsCaptor.getValue())
                .as("3 个 chunk 必须在单次 embed 调用中批量送过去")
                .containsExactly("chunk-1 文本", "chunk-2 文本", "chunk-3 文本");

        // entity 列表与 chunks 一一对应
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatEmbeddingEntity>> entitiesCaptor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(entitiesCaptor.capture());
        List<ChatEmbeddingEntity> saved = entitiesCaptor.getValue();
        assertThat(saved).hasSize(3);

        assertThat(saved.get(0).getMessageIds()).containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(saved.get(0).getChunkText()).isEqualTo("chunk-1 文本");
        assertThat(saved.get(0).getEmbedding()).isSameAs(v1);
        assertThat(saved.get(0).getTokenCount()).isEqualTo(30);

        assertThat(saved.get(1).getMessageIds()).containsExactly(6L, 7L, 8L, 9L, 10L);
        assertThat(saved.get(1).getChunkText()).isEqualTo("chunk-2 文本");
        assertThat(saved.get(1).getEmbedding()).isSameAs(v2);
        assertThat(saved.get(1).getTokenCount()).isEqualTo(35);

        assertThat(saved.get(2).getMessageIds()).containsExactly(11L, 12L, 13L, 14L, 15L);
        assertThat(saved.get(2).getChunkText()).isEqualTo("chunk-3 文本");
        assertThat(saved.get(2).getEmbedding()).isSameAs(v3);
        assertThat(saved.get(2).getTokenCount()).isEqualTo(40);
    }

    @Test
    void index_shouldDoNothingForEmptyChunks() {
        service.index(100L, 7L, List.of());

        verifyNoInteractions(embeddingProvider);
        verifyNoInteractions(repository);
    }

    @Test
    void index_shouldDoNothingForNullChunks() {
        service.index(100L, 7L, null);

        verifyNoInteractions(embeddingProvider);
        verifyNoInteractions(repository);
    }

    @Test
    void index_shouldRethrowBusinessExceptionFromProvider() {
        MemoryChunkBuilder.Chunk chunk = new MemoryChunkBuilder.Chunk(
                List.of(1L, 2L, 3L, 4L, 5L), "chunk 文本", 30);
        BusinessException providerError =
                new BusinessException(MemoryErrCode.EMBEDDING_SERVICE_UNAVAILABLE);
        when(embeddingProvider.embed(anyList())).thenThrow(providerError);

        assertThatThrownBy(() -> service.index(100L, 7L, List.of(chunk)))
                .isSameAs(providerError);

        // 异常路径下不应触碰 repository
        verify(repository, never()).saveAll(anyList());
    }

    @Test
    void index_shouldThrowWhenVectorCountMismatchesChunksCount() {
        MemoryChunkBuilder.Chunk c1 = new MemoryChunkBuilder.Chunk(
                List.of(1L, 2L, 3L, 4L, 5L), "chunk-1", 30);
        MemoryChunkBuilder.Chunk c2 = new MemoryChunkBuilder.Chunk(
                List.of(6L, 7L, 8L, 9L, 10L), "chunk-2", 30);
        // 故意只返回 1 个向量，模拟远端协议错误
        when(embeddingProvider.embed(anyList()))
                .thenReturn(List.of(new float[]{0.1f}));

        assertThatThrownBy(() -> service.index(100L, 7L, List.of(c1, c2)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrCode())
                        .isEqualTo(MemoryErrCode.EMBEDDING_SERVICE_UNAVAILABLE));

        // 数量不一致时不应入库
        verify(repository, never()).saveAll(anyList());
    }
}
