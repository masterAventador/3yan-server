package com.sanyan.memory.internal.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanyan.memory.event.MessageEmbeddingIndexListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Plan 2 Task P5：消费 Redis Stream {@code sanyan:memory:rag:index-queue}
 * （由 {@link MessageEmbeddingIndexListener} 入队），调
 * {@link MemoryEmbeddingService#index} 落库到 {@code chat_embeddings}。
 *
 * <p>简化策略（spec §P5）：用 Spring {@code @Scheduled(fixedDelay=10000)} 每 10 秒 polling 消费，
 * 不用 consumer group + StreamMessageListenerContainer——后者配置复杂、需要重平衡、
 * 测试也难写。本实现：
 * <ul>
 *   <li>{@code XRANGE} 读出当前所有 record（不分页，MVP 阶段队列不会很大）</li>
 *   <li>逐条 deserialize → 调 {@link MemoryEmbeddingService#index} → {@code XDEL} 删除</li>
 *   <li>调用失败：retry-count++ 重新 XADD 一条新 record，删掉原 record</li>
 *   <li>retry-count 达 {@link #MAX_RETRIES}（=5）：写 {@code [RAG_INDEX_DLQ]} ERROR 日志 + 删除 record（不再入队）</li>
 * </ul>
 *
 * <p><b>顺序处理</b>：本 worker 是 Spring 单例 + Scheduler 默认单线程，多条 record 串行处理。
 * 单条慢调用会阻塞队列消费——但 MVP 量级远未达瓶颈，未来量大可加 consumer group 并行。
 *
 * <p><b>不在事务里</b>：本 worker 没有 {@code @Transactional}，每条 record 单独处理，
 * 失败一条不影响其他。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RagIndexWorker {

    /** 同 {@link MessageEmbeddingIndexListener#INDEX_QUEUE_KEY}。 */
    static final String INDEX_QUEUE_KEY = "sanyan:memory:rag:index-queue";

    /** 同 {@link MessageEmbeddingIndexListener#STREAM_PAYLOAD_FIELD}。 */
    static final String STREAM_PAYLOAD_FIELD = "payload";

    /** 最大重试次数；达到此值不再 XADD，落 DLQ 日志后删除。 */
    static final int MAX_RETRIES = 5;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final StringRedisTemplate redis;
    private final MemoryEmbeddingService embeddingService;

    @Scheduled(fixedDelay = 10000)
    public void poll() {
        try {
            List<MapRecord<String, Object, Object>> records = redis.opsForStream()
                    .range(INDEX_QUEUE_KEY, Range.unbounded());
            if (records == null || records.isEmpty()) {
                return;
            }
            for (MapRecord<String, Object, Object> record : records) {
                Object payloadObj = record.getValue().get(STREAM_PAYLOAD_FIELD);
                if (payloadObj == null) {
                    log.warn("RAG 索引 record 缺 {} 字段，跳过: id={}", STREAM_PAYLOAD_FIELD, record.getId());
                    redis.opsForStream().delete(INDEX_QUEUE_KEY, record.getId());
                    continue;
                }
                String payload = payloadObj.toString();
                processRecord(record.getId(), payload);
            }
        } catch (Exception e) {
            // Redis 不可用 / 配置缺失 / 测试环境无 stream — 全部安静跳过，
            // 下次 fixedDelay 触发重试。生产环境 Redis 真正异常时会持续 log.debug，
            // 由 Redis 健康检查 / 外部监控发现，避免每 10s 一条 ERROR stack trace 噪音。
            log.debug("RagIndexWorker.poll skipped (Redis 不可用?): {}", e.getMessage());
        }
    }

    private void processRecord(RecordId recordId, String payload) {
        MessageEmbeddingIndexListener.ChunkIndexJob job;
        try {
            job = MAPPER.readValue(payload, MessageEmbeddingIndexListener.ChunkIndexJob.class);
        } catch (Exception e) {
            log.error("RAG 索引 record 反序列化失败，丢弃: id={} payload={}", recordId, payload, e);
            redis.opsForStream().delete(INDEX_QUEUE_KEY, recordId);
            return;
        }
        try {
            MemoryChunkBuilder.Chunk chunk = new MemoryChunkBuilder.Chunk(
                    job.messageIds(), job.chunkText(), job.tokenCount());
            embeddingService.index(job.userId(), job.characterId(), List.of(chunk));
            // 成功 → 删除 record
            redis.opsForStream().delete(INDEX_QUEUE_KEY, recordId);
        } catch (Exception err) {
            handleRetry(recordId, job, err);
        }
    }

    private void handleRetry(RecordId recordId, MessageEmbeddingIndexListener.ChunkIndexJob job, Exception err) {
        try {
            if (job.retryCount() >= MAX_RETRIES) {
                // 达到上限 → DLQ 日志 + 删除（不再重试）
                log.error("[RAG_INDEX_DLQ] user={} char={} msgIds={} chunkText={} retryCount={} err={}",
                        job.userId(), job.characterId(), job.messageIds(),
                        job.chunkText(), job.retryCount(), err.getMessage());
                redis.opsForStream().delete(INDEX_QUEUE_KEY, recordId);
                return;
            }
            MessageEmbeddingIndexListener.ChunkIndexJob retryJob = new MessageEmbeddingIndexListener.ChunkIndexJob(
                    job.userId(),
                    job.characterId(),
                    job.messageIds(),
                    job.chunkText(),
                    job.tokenCount(),
                    job.retryCount() + 1);
            String newPayload = MAPPER.writeValueAsString(retryJob);
            redis.opsForStream().add(INDEX_QUEUE_KEY, Map.of(STREAM_PAYLOAD_FIELD, newPayload));
            redis.opsForStream().delete(INDEX_QUEUE_KEY, recordId);
            log.warn("RAG 索引重试 {} 次: user={} char={} err={}",
                    retryJob.retryCount(), job.userId(), job.characterId(), err.getMessage());
        } catch (Exception e2) {
            // retry handling 自身失败：保留原 record（不删），下次 poll 时仍能拿到重新处理
            log.error("RAG 索引 retry handling 失败 id={} job={}", recordId, job, e2);
        }
    }
}
