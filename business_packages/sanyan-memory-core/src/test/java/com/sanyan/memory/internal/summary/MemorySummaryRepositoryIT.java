package com.sanyan.memory.internal.summary;

import com.sanyan.common.test.PostgresTestcontainerSupport;
import com.sanyan.common.test.TestApplication;
import com.sanyan.memory.internal.summary.fixtures.MemorySummaryTestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * Plan 2 Task N1：MemorySummaryRepository 集成测试。
 *
 * <p>用 Testcontainers PG 而非 H2，原因：
 * <ul>
 *   <li>真实 PG 行为更可信（生产是 PG 17，避免 H2 兼容陷阱）</li>
 *   <li>与 V5__init_memory_summaries.sql 的字段类型 / nullability 对齐验证</li>
 *   <li>后续 P 阶段会引入 pgvector，统一用 PG 测试链路</li>
 * </ul>
 *
 * <p>{@code @DataJpaTest} 默认会用 H2 替换 dataSource，{@code @AutoConfigureTestDatabase(replace = NONE)}
 * 关闭这层替换，{@code @DynamicPropertySource} 把 spring.datasource.* 指向 Testcontainer PG。
 *
 * <p>Schema 由 Hibernate {@code ddl-auto=create-drop}（@DataJpaTest 默认）从 Entity 注解自动生成——
 * 这里**只验证 Entity ↔ Repository ↔ JPA 查询链路**；Entity 是否对齐 V5 migration schema 由
 * bootstrap 模块的 V5MigrationIT 单独保证。
 *
 * <p>{@code @EntityScan} + {@code @EnableJpaRepositories}：TestApplication 在 com.sanyan.common.test 包，
 * 默认扫不到 com.sanyan.memory，需要显式指定。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@ContextConfiguration(classes = TestApplication.class)
@EntityScan(basePackages = "com.sanyan.memory")
@EnableJpaRepositories(basePackages = "com.sanyan.memory")
class MemorySummaryRepositoryIT extends PostgresTestcontainerSupport {

    @DynamicPropertySource
    static void pgProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestcontainerSupport::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestcontainerSupport::username);
        registry.add("spring.datasource.password", PostgresTestcontainerSupport::password);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        // 关闭 Flyway（本模块测试不依赖 V5 migration 文件，schema 由 Hibernate ddl-auto 生成）
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @Autowired
    private MemorySummaryRepository repository;

    @Test
    void saveAndFindById_persistsAllFields() {
        MemorySummaryEntity saved = repository.save(MemorySummaryTestFixtures.validSummary());

        assertThat(saved.getId()).as("save 后 id 应被回填").isNotNull();
        assertThat(saved.getCreatedAt()).as("@PrePersist 应默认填充 createdAt").isNotNull();

        Optional<MemorySummaryEntity> loaded = repository.findById(saved.getId());
        assertThat(loaded).isPresent();
        MemorySummaryEntity e = loaded.get();
        assertThat(e.getUserId()).isEqualTo(1L);
        assertThat(e.getCharacterId()).isEqualTo(1L);
        assertThat(e.getPeriodStartMessageId()).isEqualTo(1L);
        assertThat(e.getPeriodEndMessageId()).isEqualTo(30L);
        assertThat(e.getSummaryText()).isEqualTo("测试摘要文本");
        assertThat(e.getMessageCount()).isEqualTo(30);
    }

    @Test
    void findFirstByUserIdAndCharacterIdOrderByCreatedAtDesc_returnsLatest() throws Exception {
        // 同一 user × character，连续插三条；createdAt 按插入顺序递增（用真实时钟，靠插入顺序保证）
        MemorySummaryEntity first = MemorySummaryTestFixtures.summaryWithMessageCount(10);
        repository.saveAndFlush(first);
        Thread.sleep(10); // 保证 createdAt 不同（PG TIMESTAMP 精度足够）

        MemorySummaryEntity second = MemorySummaryTestFixtures.summaryWithMessageCount(20);
        repository.saveAndFlush(second);
        Thread.sleep(10);

        MemorySummaryEntity third = MemorySummaryTestFixtures.summaryWithMessageCount(30);
        repository.saveAndFlush(third);

        Optional<MemorySummaryEntity> latest =
                repository.findFirstByUserIdAndCharacterIdOrderByCreatedAtDesc(1L, 1L);

        assertThat(latest).isPresent();
        assertThat(latest.get().getId()).as("应返回最新插入的那条").isEqualTo(third.getId());
        assertThat(latest.get().getMessageCount()).isEqualTo(30);
    }

    @Test
    void findFirstByUserIdAndCharacterIdOrderByCreatedAtDesc_isolatesByUserAndCharacter() {
        // 用户 1 × 角色 1：一条
        repository.saveAndFlush(MemorySummaryTestFixtures.validSummary());

        // 用户 2 × 角色 1：另一条
        MemorySummaryEntity otherUser = MemorySummaryTestFixtures.summaryForUser(2L);
        repository.saveAndFlush(otherUser);

        // 查用户 1 × 角色 1，应只看到用户 1 的那条
        Optional<MemorySummaryEntity> forUser1 =
                repository.findFirstByUserIdAndCharacterIdOrderByCreatedAtDesc(1L, 1L);
        assertThat(forUser1).isPresent();
        assertThat(forUser1.get().getUserId()).isEqualTo(1L);

        // 查用户 2 × 角色 1，应只看到用户 2 的那条
        Optional<MemorySummaryEntity> forUser2 =
                repository.findFirstByUserIdAndCharacterIdOrderByCreatedAtDesc(2L, 1L);
        assertThat(forUser2).isPresent();
        assertThat(forUser2.get().getUserId()).isEqualTo(2L);

        // 查用户 1 × 角色 999（不存在的角色），应空
        Optional<MemorySummaryEntity> forMissingCharacter =
                repository.findFirstByUserIdAndCharacterIdOrderByCreatedAtDesc(1L, 999L);
        assertThat(forMissingCharacter).isEmpty();
    }
}
