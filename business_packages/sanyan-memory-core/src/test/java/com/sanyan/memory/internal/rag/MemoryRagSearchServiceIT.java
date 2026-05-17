package com.sanyan.memory.internal.rag;

import com.sanyan.common.test.PostgresTestcontainerSupport;
import com.sanyan.common.test.TestApplication;
import com.sanyan.embedding.EmbeddingApi;
import com.sanyan.memory.dto.MemoryFragment;
import com.sanyan.memory.MemoryConstants;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * Plan 2 Task P4：MemoryRagSearchService 集成测试。
 *
 * <p>关注点 = native SQL 行为：cosine distance 操作符 {@code <=>}、Top K 排序、
 * 相似度阈值过滤、用户/角色隔离。这些都<b>不能在单测验证</b>——必须真的跑在 pgvector 上。
 *
 * <p>测试套路：
 * <ul>
 *   <li>用受控的简单向量（2 维，便于推算相似度）插入若干 chunks</li>
 *   <li>Mock {@link EmbeddingApi} 返回已知 query 向量</li>
 *   <li>断言返回的 {@link MemoryFragment} 顺序 / 数量 / 相似度落在预期</li>
 * </ul>
 *
 * <p><b>关于维度：</b>V7 schema 写死了 {@code vector(1024)}。但 PG 接受任何维度的字面量
 * 输入，只要写入 / 查询时维度一致即可（IT 这里 query 和 stored chunk 都用 1024 维，
 * 但只在前两个分量上做差异化设计，其余补 0，相似度按前两个分量主导）。
 *
 * <p>{@code @MockBean} 替换 EmbeddingApi：真实实现（SiliconFlowEmbeddingProvider 委托）是 @Component
 * 但需要 RestClient + retry 配置，启动太重，本 IT 只关心 service → repo 的 SQL 链路。
 *
 * <p>本 IT 跟 {@link ChatEmbeddingRepositoryIT} 共用同一个 Testcontainer + 同一套 Flyway 配置。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@ContextConfiguration(classes = TestApplication.class)
@EntityScan(basePackages = "com.sanyan.memory")
@EnableJpaRepositories(basePackages = "com.sanyan.memory")
@Import(MemoryRagSearchService.class)
class MemoryRagSearchServiceIT extends PostgresTestcontainerSupport {

    @DynamicPropertySource
    static void pgProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestcontainerSupport::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestcontainerSupport::username);
        registry.add("spring.datasource.password", PostgresTestcontainerSupport::password);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("spring.flyway.clean-disabled", () -> "false");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @MockBean
    private EmbeddingApi embeddingApi;

    @Autowired
    private ChatEmbeddingRepository repository;

    @Autowired
    private MemoryRagSearchService service;

    /**
     * 在 1024 维向量的前两个分量上做有控差异化，其余补 0。
     * 用单位向量便于推算 cosine distance（dot product 即等价 cos sim，省 norm 因子）。
     */
    private static float[] vec2d(float x, float y) {
        float[] v = new float[MemoryConstants.EMBEDDING_DIM];
        v[0] = x;
        v[1] = y;
        return v;
    }

    private void insertChunk(Long userId, Long characterId, String text, float[] vec) {
        ChatEmbeddingEntity e = new ChatEmbeddingEntity();
        e.setUserId(userId);
        e.setCharacterId(characterId);
        e.setMessageIds(new Long[]{1L, 2L, 3L, 4L, 5L});
        e.setChunkText(text);
        e.setEmbedding(vec);
        e.setTokenCount(50);
        repository.save(e);
    }

    @Test
    void search_shouldReturnTopKChunksOrderedBySimilarity() {
        // 给 user=1 char=1 插 3 条 chunk，向量分别指向不同方向
        // query=[1,0] 时与 [1,0] cos=1（distance 0）、与 [0.9,0.1] 接近 1、与 [0,1] cos=0（distance 1）
        insertChunk(1L, 1L, "聊养猫", vec2d(1f, 0f));         // 最相关
        insertChunk(1L, 1L, "聊代码", vec2d(0.9f, 0.1f));     // 次相关
        insertChunk(1L, 1L, "聊旅行", vec2d(0f, 1f));         // 最不相关，cos=0 应被 0.6 阈值过滤
        repository.flush();

        // Mock query 向量 = [1, 0, 0, ...]
        when(embeddingApi.embed(anyList())).thenReturn(List.of(vec2d(1f, 0f)));

        List<MemoryFragment> result = service.search(1L, 1L, "再聊聊猫");

        // [0,1] 的 cos 是 0，被 RAG_MIN_COS_SIM(0.6) 阈值过滤，所以只返回 2 条
        assertThat(result).hasSize(2);
        // 顺序：相似度高的在前
        assertThat(result.get(0).chunkText()).isEqualTo("聊养猫");
        assertThat(result.get(1).chunkText()).isEqualTo("聊代码");
        // 相似度数值合理
        assertThat(result.get(0).cosineSimilarity())
                .as("[1,0] · [1,0] cos sim 应几乎为 1")
                .isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-4));
        assertThat(result.get(1).cosineSimilarity())
                .as("[1,0] · [0.9,0.1] cos sim 应大于阈值小于 1")
                .isGreaterThan(MemoryConstants.RAG_MIN_COS_SIM)
                .isLessThan(1.0);
        assertThat(result.get(0).occurredAt())
                .as("created_at 应正确反序列化为 Instant")
                .isNotNull();
    }

    @Test
    void search_shouldLimitResultsToRagTopK() {
        // 插 6 条都跟 query 高度相似（cos ≈ 1），验证 LIMIT = RAG_TOP_K = 5
        for (int i = 0; i < 6; i++) {
            insertChunk(2L, 1L, "近似片段-" + i, vec2d(1f, i * 0.001f));
        }
        repository.flush();

        when(embeddingApi.embed(anyList())).thenReturn(List.of(vec2d(1f, 0f)));

        List<MemoryFragment> result = service.search(2L, 1L, "test");

        assertThat(result)
                .as("LIMIT 必须严格等于 MemoryConstants.RAG_TOP_K")
                .hasSize(MemoryConstants.RAG_TOP_K);
    }

    @Test
    void search_shouldIsolateBetweenUsersAndCharacters() {
        // (user=10, char=1) 插一条最相关
        insertChunk(10L, 1L, "user10-char1 高相关", vec2d(1f, 0f));
        // (user=10, char=2) 插一条最相关（不应被 char=1 召回）
        insertChunk(10L, 2L, "user10-char2 高相关", vec2d(1f, 0f));
        // (user=11, char=1) 插一条最相关（不应被 user=10 召回）
        insertChunk(11L, 1L, "user11-char1 高相关", vec2d(1f, 0f));
        repository.flush();

        when(embeddingApi.embed(anyList())).thenReturn(List.of(vec2d(1f, 0f)));

        List<MemoryFragment> result = service.search(10L, 1L, "test");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).chunkText()).isEqualTo("user10-char1 高相关");
    }

    @Test
    void search_shouldReturnEmptyWhenNoChunksExist() {
        when(embeddingApi.embed(anyList())).thenReturn(List.of(vec2d(1f, 0f)));

        List<MemoryFragment> result = service.search(999L, 999L, "test");

        assertThat(result).isEmpty();
    }
}
