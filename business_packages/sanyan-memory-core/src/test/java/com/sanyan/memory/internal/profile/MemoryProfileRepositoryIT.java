package com.sanyan.memory.internal.profile;

import com.sanyan.common.test.PostgresTestcontainerSupport;
import com.sanyan.common.test.TestApplication;
import com.sanyan.memory.internal.profile.fixtures.MemoryProfileTestFixtures;
import jakarta.persistence.EntityManager;
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
 * Plan 2.5：MemoryProfileRepository 集成测试（适配 free-form summary 重构）。
 *
 * <p>验证 4 个核心场景：
 * <ol>
 *   <li>{@code saveAndFindByCompositePk_persistsAllFields}：复合 PK (userId, characterId) + 字段往返</li>
 *   <li>{@code findByUserIdAndCharacterId_returnsOptional}：派生查询方法存在 + 不存在返回 Optional.empty</li>
 *   <li>{@code summaryTextRoundTrip_preservesLongFreeFormText}：TEXT 列保存长段落画像 + Unicode 字符往返</li>
 *   <li>{@code optimisticLock_versionIncrementsOnUpdate}：@Version 字段更新后自增</li>
 * </ol>
 *
 * <p>用 Testcontainers PG 而非 H2，原因：
 * <ul>
 *   <li>与 V8 migration 字段类型对齐（TEXT NOT NULL DEFAULT ''）</li>
 *   <li>生产是 PG 17</li>
 * </ul>
 *
 * <p>Schema 是否对齐 V8 migration（summary_text TEXT NOT NULL、复合 PK、version、updated_at NOT NULL）
 * 由 bootstrap 模块的 V8MigrationIT 单独保证；本测试只关心 Entity ↔ Repository ↔ JPA 链路。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@ContextConfiguration(classes = TestApplication.class)
@EntityScan(basePackages = "com.sanyan.memory")
@EnableJpaRepositories(basePackages = "com.sanyan.memory")
class MemoryProfileRepositoryIT extends PostgresTestcontainerSupport {

    @DynamicPropertySource
    static void pgProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestcontainerSupport::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestcontainerSupport::username);
        registry.add("spring.datasource.password", PostgresTestcontainerSupport::password);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @Autowired
    private MemoryProfileRepository repository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void saveAndFindByCompositePk_persistsAllFields() {
        MemoryProfileEntity saved = repository.save(
                MemoryProfileTestFixtures.profileWithSummary("默认画像内容"));

        assertThat(saved.getUpdatedAt()).as("@PrePersist 应默认填充 updatedAt").isNotNull();
        assertThat(saved.getVersion()).as("@Version 初次保存应为 0").isEqualTo(0);

        // 用复合 PK 重新查
        MemoryProfileId pk = new MemoryProfileId(1L, 1L);
        Optional<MemoryProfileEntity> loaded = repository.findById(pk);
        assertThat(loaded).isPresent();
        MemoryProfileEntity e = loaded.get();
        assertThat(e.getUserId()).isEqualTo(1L);
        assertThat(e.getCharacterId()).isEqualTo(1L);
        assertThat(e.getSummaryText()).isEqualTo("默认画像内容");
    }

    @Test
    void findByUserIdAndCharacterId_returnsOptional() {
        repository.save(MemoryProfileTestFixtures.validProfile());

        Optional<MemoryProfileEntity> hit = repository.findByUserIdAndCharacterId(1L, 1L);
        assertThat(hit).as("已存在的 (user, character) 应返回非空 Optional").isPresent();
        assertThat(hit.get().getUserId()).isEqualTo(1L);

        Optional<MemoryProfileEntity> miss = repository.findByUserIdAndCharacterId(999L, 999L);
        assertThat(miss).as("不存在的 (user, character) 应返回空 Optional").isEmpty();
    }

    @Test
    void summaryTextRoundTrip_preservesLongFreeFormText() {
        // 构造一段长画像（含 Unicode + 换行 + 空格），验证 TEXT 列完整保真
        String richSummary = """
                用户名叫张三，前端工程师，住北京。
                家里养了一只叫橘子的美短猫，最近因为面试焦虑。
                喜欢日料，特别是寿司；爱看《银魂》；偶尔玩 PS5。
                Emoji 测试：😺🍣🎮""";

        MemoryProfileEntity input = MemoryProfileTestFixtures.profileWithSummary(richSummary);
        repository.saveAndFlush(input);

        // 清缓存，强制从 DB 重新加载
        entityManager.clear();

        Optional<MemoryProfileEntity> loaded = repository.findByUserIdAndCharacterId(1L, 1L);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getSummaryText())
                .as("TEXT 列应完整保真长段落 + Unicode + 换行")
                .isEqualTo(richSummary);
    }

    @Test
    void optimisticLock_versionIncrementsOnUpdate() {
        MemoryProfileEntity initial = repository.saveAndFlush(
                MemoryProfileTestFixtures.profileWithSummary("v0"));
        Integer v0 = initial.getVersion();
        assertThat(v0).as("初次保存 version=0（JPA @Version 起始值）").isEqualTo(0);

        // 第一次更新：改 summaryText
        initial.setSummaryText("v1");
        repository.saveAndFlush(initial);
        entityManager.clear();

        MemoryProfileEntity afterFirstUpdate =
                repository.findByUserIdAndCharacterId(1L, 1L).orElseThrow();
        assertThat(afterFirstUpdate.getVersion())
                .as("第一次更新后 version 应自增到 1")
                .isEqualTo(1);

        // 第二次更新
        afterFirstUpdate.setSummaryText("v2");
        repository.saveAndFlush(afterFirstUpdate);
        entityManager.clear();

        MemoryProfileEntity afterSecondUpdate =
                repository.findByUserIdAndCharacterId(1L, 1L).orElseThrow();
        assertThat(afterSecondUpdate.getVersion())
                .as("第二次更新后 version 应自增到 2")
                .isEqualTo(2);
    }
}
