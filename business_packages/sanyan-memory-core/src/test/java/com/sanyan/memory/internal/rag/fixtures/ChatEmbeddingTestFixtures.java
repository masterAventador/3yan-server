package com.sanyan.memory.internal.rag.fixtures;

import com.sanyan.memory.internal.rag.ChatEmbeddingEntity;

import java.util.Random;

/**
 * ChatEmbeddingEntity 的 Object Mother（java-backend-business-layer.md §5.2）。
 *
 * <p>测试中需要 ChatEmbeddingEntity 时一律通过这里构造，禁止裸 new + setter 散落各处。
 * 修字段默认值时只需改这里，所有测试用例自动跟随。
 *
 * <p>方法语义约定：
 * <ul>
 *   <li>{@link #validEmbedding()}：默认有效切片（user=1 / character=1 / 5 条消息 / 1024 维全 0 向量）</li>
 *   <li>{@link #embeddingForUserAndCharacter(Long, Long)}：指定 user / character 的有效切片（用于隔离查询测试）</li>
 *   <li>{@link #zeros(int)}：全 0 向量（默认占位）</li>
 *   <li>{@link #randomUnit(int, long)}：种子化的伪随机向量（值域 -1..1，用于"读出和写入完全一致"断言）</li>
 * </ul>
 *
 * <p>维度 1024 = BGE-M3 输出维度，与 V7 schema {@code vector(1024)} 对齐。
 */
public final class ChatEmbeddingTestFixtures {

    private ChatEmbeddingTestFixtures() {}

    public static ChatEmbeddingEntity validEmbedding() {
        ChatEmbeddingEntity e = new ChatEmbeddingEntity();
        e.setUserId(1L);
        e.setCharacterId(1L);
        e.setMessageIds(new Long[]{1L, 2L, 3L, 4L, 5L});
        e.setChunkText("用户和小婉聊了关于猫的话题");
        e.setEmbedding(zeros(1024));
        e.setTokenCount(50);
        return e;
    }

    public static ChatEmbeddingEntity embeddingForUserAndCharacter(Long userId, Long characterId) {
        ChatEmbeddingEntity e = validEmbedding();
        e.setUserId(userId);
        e.setCharacterId(characterId);
        return e;
    }

    public static float[] zeros(int dim) {
        return new float[dim];
    }

    /**
     * 种子化的伪随机向量，值域 -1..1。
     *
     * <p>用 {@link Random} + 固定种子保证测试可重复；用于"读出和写入完全一致"的往返断言
     * （全 0 向量过于退化，无法暴露浮点序列化精度问题）。
     */
    public static float[] randomUnit(int dim, long seed) {
        Random r = new Random(seed);
        float[] v = new float[dim];
        for (int i = 0; i < dim; i++) {
            v[i] = r.nextFloat() * 2 - 1;
        }
        return v;
    }
}
