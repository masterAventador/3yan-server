package com.sanyan.memory.internal.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanyan.memory.event.MessageEmbeddingIndexListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plan 2 Task P5：{@link RagIndexWorker} Mockito 单测。
 *
 * <p>覆盖路径（见 spec §P5）：
 * <ul>
 *   <li>stream 空 → 不调 EmbeddingService、不删 record</li>
 *   <li>stream 有一个 job → 调 EmbeddingService.index → 删除 record</li>
 *   <li>EmbeddingService 抛异常 → retry-count++ 重新 XADD + 删除原 record</li>
 *   <li>retry-count 达 5 → 写 [RAG_INDEX_DLQ] ERROR 日志 + 删除 record（不再 XADD）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class RagIndexWorkerTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private StreamOperations<String, Object, Object> streamOps;
    @Mock
    private MemoryEmbeddingService embeddingService;

    @InjectMocks
    private RagIndexWorker worker;

    private static final String INDEX_QUEUE_KEY = "sanyan:memory:rag:index-queue";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach
    void setUp() {
        when(redis.opsForStream()).thenReturn(streamOps);
    }

    @Test
    void poll_emptyStream_doesNothing() {
        when(streamOps.range(eq(INDEX_QUEUE_KEY), any(Range.class))).thenReturn(List.of());

        worker.poll();

        verify(embeddingService, never()).index(anyLong(), anyLong(), anyList());
    }

    @Test
    void poll_oneSuccessJob_callsIndexAndDeletesRecord() throws Exception {
        MessageEmbeddingIndexListener.ChunkIndexJob job = new MessageEmbeddingIndexListener.ChunkIndexJob(
                100L, 1L, List.of(1L, 2L, 3L, 4L, 5L), "chunk-text", 60, 0);
        RecordId id = RecordId.of("1-0");
        Map<Object, Object> body = Map.of("payload", MAPPER.writeValueAsString(job));
        MapRecord<String, Object, Object> record = MapRecord.create(INDEX_QUEUE_KEY, body).withId(id);
        when(streamOps.range(eq(INDEX_QUEUE_KEY), any(Range.class))).thenReturn(List.of(record));

        worker.poll();

        // 调 embedding.index
        ArgumentCaptor<List<MemoryChunkBuilder.Chunk>> chunksCaptor = ArgumentCaptor.forClass(List.class);
        verify(embeddingService).index(eq(100L), eq(1L), chunksCaptor.capture());
        List<MemoryChunkBuilder.Chunk> passed = chunksCaptor.getValue();
        assertThat(passed).hasSize(1);
        assertThat(passed.get(0).messageIds()).containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(passed.get(0).chunkText()).isEqualTo("chunk-text");
        assertThat(passed.get(0).tokenCount()).isEqualTo(60);

        // 删除原 record
        verify(streamOps).delete(INDEX_QUEUE_KEY, id);
        // 没有 retry XADD
        verify(streamOps, never()).add(eq(INDEX_QUEUE_KEY), any(Map.class));
    }

    @Test
    void poll_indexThrows_retryCountIncremented_andReAdded() throws Exception {
        MessageEmbeddingIndexListener.ChunkIndexJob job = new MessageEmbeddingIndexListener.ChunkIndexJob(
                200L, 1L, List.of(10L, 11L, 12L, 13L, 14L), "ct", 50, 0);
        RecordId id = RecordId.of("2-0");
        Map<Object, Object> body = Map.of("payload", MAPPER.writeValueAsString(job));
        MapRecord<String, Object, Object> record = MapRecord.create(INDEX_QUEUE_KEY, body).withId(id);
        when(streamOps.range(eq(INDEX_QUEUE_KEY), any(Range.class))).thenReturn(List.of(record));
        doThrow(new RuntimeException("embedding 服务不可用")).when(embeddingService)
                .index(anyLong(), anyLong(), anyList());
        when(streamOps.add(eq(INDEX_QUEUE_KEY), any(Map.class))).thenReturn(RecordId.of("3-0"));

        worker.poll();

        // 重新 XADD（retry++）
        ArgumentCaptor<Map<String, String>> mapCaptor = ArgumentCaptor.forClass(Map.class);
        verify(streamOps, times(1)).add(eq(INDEX_QUEUE_KEY), mapCaptor.capture());
        String newPayload = mapCaptor.getValue().get("payload");
        MessageEmbeddingIndexListener.ChunkIndexJob retryJob = MAPPER.readValue(
                newPayload, MessageEmbeddingIndexListener.ChunkIndexJob.class);
        assertThat(retryJob.retryCount()).isEqualTo(1);
        assertThat(retryJob.userId()).isEqualTo(200L);
        assertThat(retryJob.messageIds()).containsExactly(10L, 11L, 12L, 13L, 14L);

        // 删除原 record
        verify(streamOps).delete(INDEX_QUEUE_KEY, id);
    }

    @Test
    void poll_retryCount5_logsDlqAndDeletes_noReAdd() throws Exception {
        // 已经重试过 5 次，不再 XADD（spec：达到 MAX_RETRIES=5 即 DLQ）
        MessageEmbeddingIndexListener.ChunkIndexJob job = new MessageEmbeddingIndexListener.ChunkIndexJob(
                300L, 1L, List.of(20L, 21L, 22L, 23L, 24L), "ct", 70, 5);
        RecordId id = RecordId.of("9-0");
        Map<Object, Object> body = Map.of("payload", MAPPER.writeValueAsString(job));
        MapRecord<String, Object, Object> record = MapRecord.create(INDEX_QUEUE_KEY, body).withId(id);
        when(streamOps.range(eq(INDEX_QUEUE_KEY), any(Range.class))).thenReturn(List.of(record));
        doThrow(new RuntimeException("仍然失败")).when(embeddingService)
                .index(anyLong(), anyLong(), anyList());

        worker.poll();

        // 不再 XADD
        verify(streamOps, never()).add(eq(INDEX_QUEUE_KEY), any(Map.class));
        // 删除 record
        verify(streamOps).delete(INDEX_QUEUE_KEY, id);
    }
}
