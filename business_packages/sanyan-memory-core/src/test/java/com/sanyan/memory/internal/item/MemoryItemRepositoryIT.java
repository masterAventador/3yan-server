package com.sanyan.memory.internal.item;

import com.sanyan.memory.internal.item.fixtures.MemoryItemTestFixtures;
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

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@ContextConfiguration(classes = TestApplication.class)
@EntityScan(basePackages = "com.sanyan.memory")
@EnableJpaRepositories(basePackages = "com.sanyan.memory")
class MemoryItemRepositoryIT extends PostgresTestcontainerSupport {

    @DynamicPropertySource
    static void pgProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestcontainerSupport::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestcontainerSupport::username);
        registry.add("spring.datasource.password", PostgresTestcontainerSupport::password);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired MemoryItemRepository repo;
    @Autowired TestEntityManager em;

    @Test
    void save_and_reload_should_roundtrip_enum_and_defaults() {
        MemoryItemEntity item = MemoryItemTestFixtures.planEvent(
                7L, 1L, "周三下午有面试", Instant.parse("2026-06-03T09:00:00Z"));
        Long id = repo.save(item).getId();
        em.flush();
        em.clear();

        MemoryItemEntity loaded = repo.findById(id).orElseThrow();
        assertThat(loaded.getUserId()).isEqualTo(7L);
        assertThat(loaded.getCharacterId()).isEqualTo(1L);
        assertThat(loaded.getKind()).isEqualTo(MemoryItemKind.PLAN_EVENT);
        assertThat(loaded.getContent()).isEqualTo("周三下午有面试");
        assertThat(loaded.getStatus()).isEqualTo(MemoryItemStatus.PENDING);
        assertThat(loaded.getSalientAt()).isEqualTo(Instant.parse("2026-06-03T09:00:00Z"));
        assertThat(loaded.getCreatedAt()).isNotNull();
    }

    @Test
    void findTop20ByUserIdAndCharacterIdAndStatusOrderByIdDesc_should_filter_by_status() {
        repo.save(MemoryItemTestFixtures.planEvent(8L, 1L, "周五体检", Instant.now()));
        repo.save(MemoryItemTestFixtures.emotion(8L, 1L, "最近压力大", Instant.now()));
        MemoryItemEntity done = MemoryItemTestFixtures.planEvent(8L, 1L, "已追问过的事", Instant.now());
        done.setStatus(MemoryItemStatus.DONE);
        repo.save(done);
        em.flush();
        em.clear();

        List<MemoryItemEntity> pending =
                repo.findTop20ByUserIdAndCharacterIdAndStatusOrderByIdDesc(8L, 1L, MemoryItemStatus.PENDING);

        assertThat(pending).hasSize(2);
        assertThat(pending).extracting(MemoryItemEntity::getStatus).containsOnly(MemoryItemStatus.PENDING);
    }

    @Test
    void findTop20_should_cap_at_20_most_recent_pending_ordered_by_id_desc() {
        for (int i = 0; i < 25; i++) {
            repo.save(MemoryItemTestFixtures.emotion(9L, 1L, "条目" + i, Instant.now()));
        }
        em.flush();
        em.clear();

        List<MemoryItemEntity> pending =
                repo.findTop20ByUserIdAndCharacterIdAndStatusOrderByIdDesc(9L, 1L, MemoryItemStatus.PENDING);

        // 上限 20 条，且按 id 倒序（最近的在前）
        assertThat(pending).hasSize(20);
        assertThat(pending.get(0).getId()).isGreaterThan(pending.get(19).getId());
    }
}
