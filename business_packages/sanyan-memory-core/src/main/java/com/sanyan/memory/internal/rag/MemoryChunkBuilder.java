package com.sanyan.memory.internal.rag;

import com.sanyan.chat.internal.MessageEntity;
import com.sanyan.memory.internal.MemoryConstants;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 把按时间序排好的消息切成 RAG chunk。
 *
 * <p>切片策略（详见 Plan 2 §P2）：
 * <ul>
 *   <li>每个 chunk 至少 {@link MemoryConstants#RAG_CHUNK_MIN_SIZE}=5 条消息</li>
 *   <li>每个 chunk 最多 {@link MemoryConstants#RAG_CHUNK_MAX_SIZE}=10 条消息</li>
 *   <li>每个 chunk 估算 token 数不超过 {@link MemoryConstants#RAG_CHUNK_MAX_TOKEN}=400，超过强制提前切</li>
 *   <li>不满 min 的尾部消息丢弃（等下一批触发再切，避免 1-2 条孤儿入库）</li>
 * </ul>
 *
 * <p>Token 估算策略：1 token ≈ 2 个中文字符（Plan 2 一期不引精确分词器，
 * 后续 Plan 3 如需精确可换 jtokkit/HuggingFace tokenizer）。
 */
@Component
public class MemoryChunkBuilder {

    /**
     * 把消息按时间序切成 chunks。
     *
     * @param messages 已按时间升序排好的消息列表（同一 user / character）
     * @return 切片结果；输入 null / 少于 min 时返回空 list
     */
    public List<Chunk> build(List<MessageEntity> messages) {
        if (messages == null || messages.size() < MemoryConstants.RAG_CHUNK_MIN_SIZE) {
            return List.of();
        }
        List<Chunk> result = new ArrayList<>();
        List<MessageEntity> current = new ArrayList<>();
        int currentTokens = 0;

        for (MessageEntity msg : messages) {
            int msgTokens = estimateTokens(msg.getContent());
            // 决定是否在加入本条之前先 flush current chunk
            boolean exceedsSize = current.size() >= MemoryConstants.RAG_CHUNK_MAX_SIZE;
            boolean exceedsTokens = currentTokens + msgTokens > MemoryConstants.RAG_CHUNK_MAX_TOKEN
                    && !current.isEmpty();
            if (exceedsSize || exceedsTokens) {
                if (current.size() >= MemoryConstants.RAG_CHUNK_MIN_SIZE) {
                    result.add(toChunk(current));
                }
                current = new ArrayList<>();
                currentTokens = 0;
            }
            current.add(msg);
            currentTokens += msgTokens;
        }
        // 收尾：最后一组必须达到 min size，否则丢弃
        if (current.size() >= MemoryConstants.RAG_CHUNK_MIN_SIZE) {
            result.add(toChunk(current));
        }
        return result;
    }

    private static Chunk toChunk(List<MessageEntity> msgs) {
        List<Long> ids = msgs.stream().map(MessageEntity::getId).toList();
        String text = msgs.stream()
                .map(m -> m.getSenderType() + ": " + m.getContent())
                .collect(Collectors.joining("\n"));
        int tokens = msgs.stream().mapToInt(m -> estimateTokens(m.getContent())).sum();
        return new Chunk(ids, text, tokens);
    }

    /**
     * 简单 token 估算：1 token ≈ 2 个中文字符。
     * Plan 2 一期不做精确分词，后续可换 jtokkit/HF tokenizer。
     */
    static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return Math.max(1, text.length() / 2);
    }

    /**
     * 切片结果。
     *
     * @param messageIds 该 chunk 涵盖的消息 id 列表（保留原始时间序）
     * @param chunkText  拼接后的 chunk 文本（"sender: content" 按行 join）
     * @param tokenCount 估算 token 数
     */
    public record Chunk(List<Long> messageIds, String chunkText, int tokenCount) {}
}
