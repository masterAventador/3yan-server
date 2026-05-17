package com.sanyan.character.internal;

import com.sanyan.character.internal.fixtures.RelationshipTestFixtures;
import com.sanyan.common.test.PostgresTestcontainerSupport;
import com.sanyan.common.test.TestApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * B2：RelationshipEntity + Repository roundtrip + 乐观锁递增验证。
 *
 * <p>Testcontainers PG（真实 PG 行为，FK 约束验证正确）。
 * <p>Schema 由 Flyway V1-V6 完整 migration 生成，ddl-auto=none，与生产链路对齐。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@ContextConfiguration(classes = TestApplication.class)
@EntityScan(basePackages = "com.sanyan.character")
@EnableJpaRepositories(basePackages = "com.sanyan.character")
class RelationshipRepositoryIT extends PostgresTestcontainerSupport {

    @DynamicPropertySource
    static void pgProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestcontainerSupport::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestcontainerSupport::username);
        registry.add("spring.datasource.password", PostgresTestcontainerSupport::password);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired
    RelationshipRepository repo;

    @Autowired
    TestEntityManager em;

    /**
     * 在 users 表插入一个测试用 user 行，以满足 FK relationships.user_id → users(id)。
     * V1 seed 只有 ai_character 里的小婉（id=1），users 表无 seed 数据。
     */
    private void insertTestUser(long userId, String phone) {
        em.getEntityManager().createNativeQuery(
                        "INSERT INTO users (id, phone, password, created_at) VALUES (?, ?, ?, NOW()) ON CONFLICT DO NOTHING")
                .setParameter(1, userId)
                .setParameter(2, phone)
                .setParameter(3, "x")
                .executeUpdate();
    }

    @Test
    void save_and_findById_should_roundtrip_with_composite_pk() {
        insertTestUser(999L, "13900000000");

        // V6 seed 已插入小婉 ai_character(id=1)，可直接引用 characterId=1
        RelationshipEntity rel = RelationshipTestFixtures.validRelationship(999L, 1L);
        repo.save(rel);
        em.flush();
        em.clear();

        RelationshipEntity loaded = repo.findById(new RelationshipId(999L, 1L)).orElseThrow();
        assertThat(loaded.getIntimacyScore()).isZero();
        assertThat(loaded.getCurrentStage()).isZero();
        assertThat(loaded.getVersion()).isZero();
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getUpdatedAt()).isNotNull();
    }

    @Test
    void findByUserIdAndCharacterId_should_return_existing_relationship() {
        insertTestUser(998L, "13900000002");

        repo.save(RelationshipTestFixtures.relationshipWithScore(998L, 1L, 50));
        em.flush();
        em.clear();

        RelationshipEntity found = repo.findByUserIdAndCharacterId(998L, 1L).orElseThrow();
        assertThat(found.getIntimacyScore()).isEqualTo(50);
    }

    @Test
    void version_should_increment_on_update() {
        insertTestUser(1000L, "13900000001");

        RelationshipEntity rel = repo.save(RelationshipTestFixtures.validRelationship(1000L, 1L));
        em.flush();
        assertThat(rel.getVersion()).isZero();

        rel.setIntimacyScore(10);
        repo.save(rel);
        em.flush();

        assertThat(rel.getVersion()).isEqualTo(1);
    }

    @Test
    void relationshipAtStage_fixture_should_persist_stage_and_score() {
        insertTestUser(997L, "13900000003");

        RelationshipEntity rel = RelationshipTestFixtures.relationshipAtStage(997L, 1L, 2, 300);
        repo.save(rel);
        em.flush();
        em.clear();

        RelationshipEntity loaded = repo.findById(new RelationshipId(997L, 1L)).orElseThrow();
        assertThat(loaded.getCurrentStage()).isEqualTo((short) 2);
        assertThat(loaded.getIntimacyScore()).isEqualTo(300);
    }
}
