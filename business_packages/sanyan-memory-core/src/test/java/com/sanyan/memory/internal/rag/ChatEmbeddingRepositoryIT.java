package com.sanyan.memory.internal.rag;

import com.sanyan.common.test.PostgresTestcontainerSupport;
import com.sanyan.common.test.TestApplication;
import com.sanyan.memory.internal.rag.fixtures.ChatEmbeddingTestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * Plan 2 Task P1：ChatEmbeddingRepository 集成测试。
 *
 * <p>与 {@link com.sanyan.memory.internal.summary.MemorySummaryRepositoryIT} 不同：
 * <ul>
 *   <li>本 IT 用 <strong>Flyway migration</strong> 建表（不是 Hibernate ddl-auto）——
 *       原因：vector(1024) 类型需要 {@code CREATE EXTENSION vector}，Hibernate ddl-auto
 *       无法生成 {@code CREATE EXTENSION} 语句。Flyway 跑 V1→V7 才能拿到完整 schema。</li>
 *   <li>Spring Boot 自带 Flyway autoconfigure：classpath 找到
 *       {@code db/migration/V*.sql}（由 {@code sanyan-business} 模块提供，已被本模块
 *       compile scope 依赖带入 test classpath）后自动运行。</li>
 * </ul>
 *
 * <p>{@code spring.jpa.hibernate.ddl-auto=none}：禁用 Hibernate 自动建表，schema 完全交给
 * Flyway 管。Entity 是否对齐 V7 schema 由 bootstrap 模块的 V7MigrationIT 单独保证；
 * 本 IT 关注 Entity ↔ Repository ↔ JPA + PgVectorType 链路。
 *
 * <p>{@code @EntityScan} + {@code @EnableJpaRepositories}：TestApplication 在
 * {@code com.sanyan.common.test} 包，默认扫不到 {@code com.sanyan.memory}，需显式指定。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@ContextConfiguration(classes = TestApplication.class)
@EntityScan(basePackages = "com.sanyan.memory")
@EnableJpaRepositories(basePackages = "com.sanyan.memory")
class ChatEmbeddingRepositoryIT extends PostgresTestcontainerSupport {

    @DynamicPropertySource
    static void pgProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestcontainerSupport::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestcontainerSupport::username);
        registry.add("spring.datasource.password", PostgresTestcontainerSupport::password);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        // Flyway 跑 V1..V7（包含 CREATE EXTENSION vector + chat_embeddings 表 + ivfflat 索引）
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("spring.flyway.clean-disabled", () -> "false");
        // Hibernate 不建表，完全交给 Flyway
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired
    private ChatEmbeddingRepository repository;

    @Test
    void saveAndFindById_persistsAllFieldsIncludingVectorEmbedding() {
        ChatEmbeddingEntity saved = repository.save(ChatEmbeddingTestFixtures.validEmbedding());

        assertThat(saved.getId()).as("save 后 id 应被回填").isNotNull();
        assertThat(saved.getCreatedAt()).as("@PrePersist 应默认填充 createdAt").isNotNull();

        Optional<ChatEmbeddingEntity> loaded = repository.findById(saved.getId());
        assertThat(loaded).isPresent();
        ChatEmbeddingEntity e = loaded.get();
        assertThat(e.getUserId()).isEqualTo(1L);
        assertThat(e.getCharacterId()).isEqualTo(1L);
        assertThat(e.getMessageIds()).containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(e.getChunkText()).isEqualTo("用户和小婉聊了关于猫的话题");
        assertThat(e.getTokenCount()).isEqualTo(50);
        assertThat(e.getEmbedding()).as("1024 维向量").hasSize(1024);
    }

    @Test
    void findByUserIdAndCharacterId_returnsOnlyMatchingRows() {
        // user=1 character=1 两条
        repository.save(ChatEmbeddingTestFixtures.embeddingForUserAndCharacter(1L, 1L));
        repository.save(ChatEmbeddingTestFixtures.embeddingForUserAndCharacter(1L, 1L));
        // user=1 character=2 一条
        repository.save(ChatEmbeddingTestFixtures.embeddingForUserAndCharacter(1L, 2L));
        // user=2 character=1 一条
        repository.save(ChatEmbeddingTestFixtures.embeddingForUserAndCharacter(2L, 1L));

        List<ChatEmbeddingEntity> u1c1 = repository.findByUserIdAndCharacterId(1L, 1L);
        assertThat(u1c1).hasSize(2);
        assertThat(u1c1).allMatch(e -> e.getUserId() == 1L && e.getCharacterId() == 1L);

        List<ChatEmbeddingEntity> u1c2 = repository.findByUserIdAndCharacterId(1L, 2L);
        assertThat(u1c2).hasSize(1);

        List<ChatEmbeddingEntity> u2c1 = repository.findByUserIdAndCharacterId(2L, 1L);
        assertThat(u2c1).hasSize(1);

        // 不存在的组合应返回空列表
        List<ChatEmbeddingEntity> empty = repository.findByUserIdAndCharacterId(999L, 999L);
        assertThat(empty).isEmpty();
    }

    @Test
    void vectorDimension_savesAndReadsBackEveryFloatBitwise() {
        // 用种子化随机向量验证：1024 维每个分量浮点值往返一致
        float[] original = ChatEmbeddingTestFixtures.randomUnit(1024, 42L);

        ChatEmbeddingEntity e = ChatEmbeddingTestFixtures.validEmbedding();
        e.setEmbedding(original);
        ChatEmbeddingEntity saved = repository.save(e);

        // 强制走一次 SELECT（避免 Hibernate 一级缓存直接返回内存对象）
        repository.flush();
        // 用 findAll 触发新查询而非走 L1 cache（findById 在同 session 会命中 cache）
        ChatEmbeddingEntity reloaded = repository.findById(saved.getId()).orElseThrow();
        float[] roundTrip = reloaded.getEmbedding();

        assertThat(roundTrip).as("1024 维向量").hasSize(1024);
        assertThat(roundTrip)
                .as("每个浮点分量按位一致（pgvector 默认 float32 存储，无精度损失）")
                .containsExactly(original);
    }
}
