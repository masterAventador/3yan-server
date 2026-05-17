package com.sanyan.memory.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanyan.chat.ChatApi;
import com.sanyan.chat.dto.MessageDto;
import com.sanyan.chat.event.MessagePersistedEvent;
import com.sanyan.memory.MemoryConstants;
import com.sanyan.memory.internal.rag.MemoryChunkBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Plan 2 Task P5：监听 {@link MessagePersistedEvent}，累积消息到达阈值后切 chunk 推入
 * Redis Stream {@code sanyan:memory:rag:index-queue}（由 {@link com.sanyan.memory.internal.rag.RagIndexWorker}
 * 异步消费 + 落库 pgvector）。
 *
 * <p>整体设计要点（详见 plan §P5）：
 * <ul>
 *   <li>{@code @TransactionalEventListener(AFTER_COMMIT)}：必须等消息事务提交后再触发，
 *       避免消息事务回滚但 chunk 已入队</li>
 *   <li>{@code @Async}：不阻塞主消息流，listener 失败不回滚消息持久化</li>
 *   <li><b>累积模型</b>：每条消息按 {@code pending:userId:characterId} key push 到 Redis list；
 *       累积量 &lt; {@link MemoryConstants#RAG_CHUNK_MIN_SIZE} 直接返回；累积量 &ge;
 *       {@link MemoryConstants#RAG_CHUNK_MAX_SIZE} 时一次性把 pending list 全部 pop 出来，
 *       通过 {@link ChatApi#findAllByIds} 回查原始消息，按 id 升序排，交给
 *       {@link MemoryChunkBuilder#build} 切片，每个 chunk 序列化成 {@link ChunkIndexJob}
 *       XADD 到 stream</li>
 *   <li><b>消息内容不放 Redis</b>：只存 message id 到 pending list，避免 Redis 占用过多内存；
 *       触发切片时再回 DB 查内容（一次批量查询）</li>
 * </ul>
 *
 * <p><b>不在本 listener 做的事</b>：
 * <ul>
 *   <li>调用 embedding HTTP / 写 pgvector：交给 {@link com.sanyan.memory.internal.rag.RagIndexWorker}
 *       异步处理，避免主消息流被远程慢调用阻塞</li>
 *   <li>失败重试：Worker 侧维护 retry-count，本 listener 仅"成功入队"</li>
 * </ul>
 *
 * <p><b>简化策略</b>（spec §P5）：用普通 Redis list + Stream + scheduled poll worker，
 * 不用 consumer group + StreamMessageListenerContainer（复杂、需要重平衡）。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MessageEmbeddingIndexListener {

    /** Redis list key 前缀：{prefix}{userId}:{characterId}，累积未切片的 message id。 */
    static final String PENDING_KEY_PREFIX = "sanyan:memory:rag:pending:";

    /** Redis Stream key：累积满 chunk 后 XADD 进去；由 {@link com.sanyan.memory.internal.rag.RagIndexWorker} 消费。 */
    static final String INDEX_QUEUE_KEY = "sanyan:memory:rag:index-queue";

    /** Stream record 的 field 名（payload 是序列化的 {@link ChunkIndexJob} JSON）。 */
    static final String STREAM_PAYLOAD_FIELD = "payload";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatApi chatApi;
    private final MemoryChunkBuilder chunkBuilder;
    private final StringRedisTemplate redis;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMessagePersisted(MessagePersistedEvent event) {
        try {
            String pendingKey = PENDING_KEY_PREFIX + event.userId() + ":" + event.characterId();
            // 1) 把本条 message id 累积到 pending list；
            // RPUSH 原子返回 push 后的 list 长度，无需再发一次 LLEN（少一次网络 RTT，也避免并发条件下 size != 实际长度）
            Long size = redis.opsForList().rightPush(pendingKey, String.valueOf(event.messageId()));
            if (size == null || size < MemoryConstants.RAG_CHUNK_MIN_SIZE) {
                // 累积量未达 min（5）→ 暂存
                return;
            }
            if (size < MemoryConstants.RAG_CHUNK_MAX_SIZE) {
                // 累积量在 [min, max) 之间 → 也暂存，等达 max 再一次性切（避免频繁触发）
                return;
            }
            // 2) 累积达 max（10）→ pop 累积 list 中的所有 id
            List<String> popped = redis.opsForList().range(pendingKey, 0, size - 1);
            redis.delete(pendingKey);  // 清空累积；后续新消息重新开始累积
            if (popped == null || popped.isEmpty()) {
                return;
            }
            List<Long> ids = popped.stream().map(Long::valueOf).toList();

            // 3) 回查原始消息，按 id 升序排（保证 chunk 内时间正序）
            // ChatApi.findAllByIds 返回不可变 List，sort 前必须拷成可变 List
            List<MessageDto> messages = new java.util.ArrayList<>(chatApi.findAllByIds(ids));
            messages.sort(Comparator.comparing(MessageDto::id));

            // 4) 切 chunks → XADD 到 stream
            List<MemoryChunkBuilder.Chunk> chunks = chunkBuilder.build(messages);
            if (chunks.isEmpty()) {
                return;
            }
            for (MemoryChunkBuilder.Chunk chunk : chunks) {
                ChunkIndexJob job = new ChunkIndexJob(
                        event.userId(),
                        event.characterId(),
                        chunk.messageIds(),
                        chunk.chunkText(),
                        chunk.tokenCount(),
                        0);
                redis.opsForStream().add(INDEX_QUEUE_KEY, Map.of(STREAM_PAYLOAD_FIELD, serialize(job)));
            }
            log.info("RAG 索引入队: user={} char={} chunks={}",
                    event.userId(), event.characterId(), chunks.size());
        } catch (Exception e) {
            // 后台任务失败不应影响主消息流——吞掉 + ERROR 日志
            log.error("RAG 索引入队失败 user={} char={} msg={}",
                    event.userId(), event.characterId(), event.messageId(), e);
        }
    }

    private static String serialize(ChunkIndexJob job) {
        try {
            return MAPPER.writeValueAsString(job);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize ChunkIndexJob", e);
        }
    }

    /**
     * 索引队列消息体（{@link com.sanyan.memory.internal.rag.RagIndexWorker} 消费）。
     *
     * @param userId      用户 id
     * @param characterId 角色 id
     * @param messageIds  chunk 涵盖的消息 id 列表
     * @param chunkText   拼好的 chunk 文本
     * @param tokenCount  估算 token 数
     * @param retryCount  重试次数（首次入队为 0；Worker 失败时 +1 后重新 XADD）
     */
    public record ChunkIndexJob(
            Long userId,
            Long characterId,
            List<Long> messageIds,
            String chunkText,
            int tokenCount,
            int retryCount) {}
}
