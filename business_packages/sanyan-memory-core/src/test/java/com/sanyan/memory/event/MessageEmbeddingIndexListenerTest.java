package com.sanyan.memory.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanyan.chat.event.MessagePersistedEvent;
import com.sanyan.chat.internal.MessageEntity;
import com.sanyan.chat.internal.MessageRepository;
import com.sanyan.chat.internal.SenderType;
import com.sanyan.memory.internal.MemoryConstants;
import com.sanyan.memory.internal.rag.MemoryChunkBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plan 2 Task P5：{@link MessageEmbeddingIndexListener} Mockito 单测。
 *
 * <p>核心验证路径（见 spec §P5）：
 * <ul>
 *   <li>累积消息 push 到 pending list（key 含 user / character）</li>
 *   <li>累积 &lt; min(5) → 不切片、不 XADD</li>
 *   <li>累积 == max(10) → 触发 build chunks + 每个 chunk XADD 到 stream + 清空 pending list</li>
 *   <li>每个 chunk 的 payload 是 JSON 序列化的 ChunkIndexJob，含 userId/characterId/messageIds/chunkText/tokenCount/retryCount=0</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class MessageEmbeddingIndexListenerTest {

    @Mock
    private MessageRepository messageRepository;
    @Mock
    private MemoryChunkBuilder chunkBuilder;
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ListOperations<String, String> listOps;
    @Mock
    private StreamOperations<String, Object, Object> streamOps;

    @InjectMocks
    private MessageEmbeddingIndexListener listener;

    private static final Long USER_ID = 100L;
    private static final Long CHARACTER_ID = 1L;
    private static final String PENDING_KEY = "sanyan:memory:rag:pending:" + USER_ID + ":" + CHARACTER_ID;
    private static final String INDEX_QUEUE_KEY = "sanyan:memory:rag:index-queue";

    @BeforeEach
    void setUp() {
        // lenient：部分用例不会调用 streamOps，避免 UnnecessaryStubbing
        lenient().when(redis.opsForList()).thenReturn(listOps);
        lenient().when(redis.opsForStream()).thenReturn(streamOps);
    }

    @Test
    void onMessagePersisted_belowMinSize_skipsChunking() {
        // 累积 4 条（< RAG_CHUNK_MIN_SIZE=5）
        when(listOps.rightPush(eq(PENDING_KEY), anyString())).thenReturn(4L);

        listener.onMessagePersisted(event(500L));

        verify(listOps).rightPush(PENDING_KEY, "500");
        // 不应查 message / build chunks / XADD
        verify(messageRepository, never()).findAllById(any());
        verify(chunkBuilder, never()).build(any());
        verify(streamOps, never()).add(eq(INDEX_QUEUE_KEY), any(Map.class));
    }

    @Test
    void onMessagePersisted_atMinSize_skipsChunking() {
        // RAG_CHUNK_MIN_SIZE=5，但 < RAG_CHUNK_MAX_SIZE=10 不触发
        when(listOps.rightPush(eq(PENDING_KEY), anyString())).thenReturn(5L);

        listener.onMessagePersisted(event(500L));

        verify(messageRepository, never()).findAllById(any());
        verify(streamOps, never()).add(eq(INDEX_QUEUE_KEY), any(Map.class));
    }

    @Test
    void onMessagePersisted_atMaxSize_triggersChunkingAndXAdd() throws Exception {
        // 累积 10 条（== RAG_CHUNK_MAX_SIZE=10）
        int size = MemoryConstants.RAG_CHUNK_MAX_SIZE;
        when(listOps.rightPush(eq(PENDING_KEY), anyString())).thenReturn((long) size);
        List<String> popped = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            popped.add(String.valueOf(1000L + i));
        }
        when(listOps.range(PENDING_KEY, 0, size - 1)).thenReturn(popped);

        List<MessageEntity> entities = buildMessageEntities(1000L, size);
        when(messageRepository.findAllById(any())).thenReturn(entities);

        MemoryChunkBuilder.Chunk chunk = new MemoryChunkBuilder.Chunk(
                List.of(1000L, 1001L, 1002L, 1003L, 1004L), "chunk-text", 80);
        when(chunkBuilder.build(any())).thenReturn(List.of(chunk));
        when(streamOps.add(eq(INDEX_QUEUE_KEY), any(Map.class))).thenReturn(RecordId.of("1-0"));

        listener.onMessagePersisted(event(1009L));

        // 清空累积 list
        verify(redis).delete(PENDING_KEY);
        // 拉取消息 → build chunks
        verify(messageRepository).findAllById(any());
        verify(chunkBuilder).build(any());
        // 每个 chunk XADD 一次
        ArgumentCaptor<Map<String, String>> mapCaptor = ArgumentCaptor.forClass(Map.class);
        verify(streamOps, times(1)).add(eq(INDEX_QUEUE_KEY), mapCaptor.capture());

        // 验证 payload JSON 含必须字段
        String payload = mapCaptor.getValue().get("payload");
        assertThat(payload).isNotBlank();
        ObjectMapper mapper = new ObjectMapper();
        MessageEmbeddingIndexListener.ChunkIndexJob job = mapper.readValue(
                payload, MessageEmbeddingIndexListener.ChunkIndexJob.class);
        assertThat(job.userId()).isEqualTo(USER_ID);
        assertThat(job.characterId()).isEqualTo(CHARACTER_ID);
        assertThat(job.messageIds()).containsExactly(1000L, 1001L, 1002L, 1003L, 1004L);
        assertThat(job.chunkText()).isEqualTo("chunk-text");
        assertThat(job.tokenCount()).isEqualTo(80);
        assertThat(job.retryCount()).isZero();
    }

    @Test
    void onMessagePersisted_multipleChunks_xAddsEachSeparately() {
        int size = MemoryConstants.RAG_CHUNK_MAX_SIZE;
        when(listOps.rightPush(eq(PENDING_KEY), anyString())).thenReturn((long) size);
        List<String> popped = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            popped.add(String.valueOf(2000L + i));
        }
        when(listOps.range(PENDING_KEY, 0, size - 1)).thenReturn(popped);
        when(messageRepository.findAllById(any())).thenReturn(buildMessageEntities(2000L, size));

        // 2 个 chunks
        MemoryChunkBuilder.Chunk c1 = new MemoryChunkBuilder.Chunk(List.of(2000L, 2001L, 2002L, 2003L, 2004L), "c1", 50);
        MemoryChunkBuilder.Chunk c2 = new MemoryChunkBuilder.Chunk(List.of(2005L, 2006L, 2007L, 2008L, 2009L), "c2", 60);
        when(chunkBuilder.build(any())).thenReturn(List.of(c1, c2));
        when(streamOps.add(eq(INDEX_QUEUE_KEY), any(Map.class))).thenReturn(RecordId.of("1-0"));

        listener.onMessagePersisted(event(2009L));

        verify(streamOps, times(2)).add(eq(INDEX_QUEUE_KEY), any(Map.class));
    }

    @Test
    void onMessagePersisted_emptyPopped_doesNotCallRepository() {
        when(listOps.rightPush(eq(PENDING_KEY), anyString())).thenReturn(10L);
        when(listOps.range(PENDING_KEY, 0, 9)).thenReturn(List.of());

        listener.onMessagePersisted(event(500L));

        verify(redis).delete(PENDING_KEY);
        verify(messageRepository, never()).findAllById(any());
        verify(streamOps, never()).add(eq(INDEX_QUEUE_KEY), any(Map.class));
    }

    @Test
    void onMessagePersisted_chunkBuilderEmpty_xAddsNothing() {
        int size = MemoryConstants.RAG_CHUNK_MAX_SIZE;
        lenient().when(listOps.rightPush(eq(PENDING_KEY), anyString())).thenReturn((long) size);
        List<String> popped = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            popped.add(String.valueOf(3000L + i));
        }
        lenient().when(listOps.range(PENDING_KEY, 0, size - 1)).thenReturn(popped);
        lenient().when(messageRepository.findAllById(any())).thenReturn(buildMessageEntities(3000L, size));
        lenient().when(chunkBuilder.build(any())).thenReturn(List.of());

        listener.onMessagePersisted(event(3009L));

        verify(streamOps, never()).add(eq(INDEX_QUEUE_KEY), any(Map.class));
    }

    private static MessagePersistedEvent event(Long messageId) {
        return new MessagePersistedEvent(messageId, USER_ID, CHARACTER_ID, SenderType.USER);
    }

    private static List<MessageEntity> buildMessageEntities(long startId, int count) {
        List<MessageEntity> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            MessageEntity m = new MessageEntity();
            m.setId(startId + i);
            m.setUserId(USER_ID);
            m.setSenderType(i % 2 == 0 ? SenderType.USER : SenderType.AI);
            m.setContent("msg-" + (startId + i));
            list.add(m);
        }
        return list;
    }
}
