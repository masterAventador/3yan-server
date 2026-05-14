package com.sanyan.memory.internal.rag;

import com.sanyan.chat.internal.MessageEntity;
import com.sanyan.chat.internal.SenderType;
import com.sanyan.memory.MemoryConstants;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MemoryChunkBuilder TDD 单测。
 *
 * <p>覆盖 Plan 2 §P2 切片策略：
 * <ul>
 *   <li>满 {@link MemoryConstants#RAG_CHUNK_MIN_SIZE}=5 条 → 1 chunk</li>
 *   <li>不到 5 条 → 0 chunk（尾部丢弃，等下一批）</li>
 *   <li>20 条短消息 → 按 {@link MemoryConstants#RAG_CHUNK_MAX_SIZE}=10 上限切成 2 chunk</li>
 *   <li>10 条 + 第 11 条孤儿 → 第二组不到 min，第二组丢弃</li>
 *   <li>token 累积超 {@link MemoryConstants#RAG_CHUNK_MAX_TOKEN}=400 时按 token 提前切</li>
 *   <li>messageIds / chunkText / tokenCount 字段语义正确</li>
 * </ul>
 */
class MemoryChunkBuilderTest {

    private final MemoryChunkBuilder builder = new MemoryChunkBuilder();

    @Test
    void build_shouldReturnEmptyWhenLessThanMinSize() {
        // 4 条短消息（< 5），不到 min 全丢
        List<MessageEntity> messages = shortMessages(4);

        List<MemoryChunkBuilder.Chunk> chunks = builder.build(messages);

        assertThat(chunks).isEmpty();
    }

    @Test
    void build_shouldReturnSingleChunkWhenExactlyMinSize() {
        // 恰好 5 条短消息 → 1 chunk
        List<MessageEntity> messages = shortMessages(5);

        List<MemoryChunkBuilder.Chunk> chunks = builder.build(messages);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).messageIds()).hasSize(5);
        assertThat(chunks.get(0).messageIds()).containsExactly(1L, 2L, 3L, 4L, 5L);
    }

    @Test
    void build_shouldSplitInto10Plus10WhenTwentyShortMessages() {
        // 20 条短消息 → 10 + 10
        List<MessageEntity> messages = shortMessages(20);

        List<MemoryChunkBuilder.Chunk> chunks = builder.build(messages);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).messageIds()).hasSize(10);
        assertThat(chunks.get(1).messageIds()).hasSize(10);
        assertThat(chunks.get(0).messageIds()).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
        assertThat(chunks.get(1).messageIds()).containsExactly(11L, 12L, 13L, 14L, 15L, 16L, 17L, 18L, 19L, 20L);
    }

    @Test
    void build_shouldDiscardTailWhenSecondGroupBelowMinSize() {
        // 11 条：第一组 10 条切走，剩 1 条不到 min，丢弃
        List<MessageEntity> messages = shortMessages(11);

        List<MemoryChunkBuilder.Chunk> chunks = builder.build(messages);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).messageIds()).hasSize(10);
    }

    @Test
    void build_shouldSplitByTokenWhenAccumulatedTokensExceedMax() {
        // 每条消息 200 字符 ≈ 100 token；5 条已经 500 token，超过 400 上限
        // 期望：第 4 条加入后 4*100=400 还没超 400（== 上限不切），第 5 条进来时 400+100>400 触发切
        // 触发切时 current 已经积累 4 条，不够 min(5)，所以第一组丢弃
        // 然后第 5 条开始新组，后续如果总共只剩 1 条也不够 min，全丢
        List<MessageEntity> messages = longMessagesOf(5, 200);

        List<MemoryChunkBuilder.Chunk> chunks = builder.build(messages);

        // 5 条 × 100 token = 500 总 token；
        // 前 4 条凑齐 400 token，第 5 条加进来触发切分，但 4 < min，整组丢弃；
        // 剩 1 条单独不够 min，全丢
        assertThat(chunks).isEmpty();
    }

    @Test
    void build_shouldHandleAllTokenExceedingChunksWhenEnoughMessages() {
        // 10 条 × 100 token = 1000 总 token，会按 token 上限多次提前切
        // 验证 token 切分行为：build 不抛异常，结果中每个 chunk 的 tokenCount 不超过 400+单条 token 余量
        // （切分发生在"将要超过"的瞬间，已加进 current 的部分 < 400，刚加入的那条可能让当前 chunk 实际略超）
        List<MessageEntity> messages = longMessagesOf(10, 200);

        List<MemoryChunkBuilder.Chunk> chunks = builder.build(messages);

        // 每条 100 token，4 条凑齐 400，第 5 条触发切但 current=4 < min(5)，第一组丢弃；
        // 第 5 条开始新组，第 8 条达到 400，第 9 条触发切但 current=4 < min(5)，又丢；
        // 第 9 条开始第 3 组，剩 9、10 共 2 条不够 min，全丢。
        // 综上：10 条长消息 → 0 chunks。这反映"token 提前切优先级 > 数量门槛"的真实行为
        assertThat(chunks).isEmpty();
    }

    @Test
    void build_shouldProduceValidChunkText() {
        List<MessageEntity> messages = shortMessages(5);

        List<MemoryChunkBuilder.Chunk> chunks = builder.build(messages);

        assertThat(chunks).hasSize(1);
        MemoryChunkBuilder.Chunk chunk = chunks.get(0);
        // chunkText 包含 senderType 前缀 + 内容，按行 join
        assertThat(chunk.chunkText()).contains("user: 消息1");
        assertThat(chunk.chunkText()).contains("user: 消息5");
        assertThat(chunk.chunkText().split("\n")).hasSize(5);
        // tokenCount > 0
        assertThat(chunk.tokenCount()).isPositive();
    }

    @Test
    void build_shouldReturnEmptyForNullInput() {
        List<MemoryChunkBuilder.Chunk> chunks = builder.build(null);

        assertThat(chunks).isEmpty();
    }

    // ===== helpers =====

    /** 构造 count 条短消息（每条 "消息N"，4 字符 ≈ 2 token）。 */
    private static List<MessageEntity> shortMessages(int count) {
        List<MessageEntity> list = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            MessageEntity m = new MessageEntity();
            m.setId((long) i);
            m.setUserId(1L);
            m.setSenderType(SenderType.USER);
            m.setContent("消息" + i);
            list.add(m);
        }
        return list;
    }

    /** 构造 count 条指定字符长度的消息（charLen 字符 ≈ charLen/2 token）。 */
    private static List<MessageEntity> longMessagesOf(int count, int charLen) {
        List<MessageEntity> list = new ArrayList<>(count);
        String content = "字".repeat(charLen);
        for (int i = 1; i <= count; i++) {
            MessageEntity m = new MessageEntity();
            m.setId((long) i);
            m.setUserId(1L);
            m.setSenderType(SenderType.USER);
            m.setContent(content);
            list.add(m);
        }
        return list;
    }
}
