package com.sanyan.proactive.internal;

import com.sanyan.common.test.PostgresTestcontainerSupport;
import com.sanyan.common.test.TestApplication;
import com.sanyan.proactive.internal.fixtures.EventPendingTestFixtures;
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

/**
 * I1：EventPendingEntity + Repository roundtrip + findByStatusAndScheduledAtBefore + findDueForUpdate(SKIP LOCKED)。
 * Testcontainers PG（SKIP LOCKED 是 PG 真实语法，H2 不支持，必须 PG）。Schema 由 Flyway V1-V11 生成。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@ContextConfiguration(classes = TestApplication.class)
@EntityScan(basePackages = "com.sanyan.proactive")
@EnableJpaRepositories(basePackages = "com.sanyan.proactive")
class EventPendingRepositoryIT extends PostgresTestcontainerSupport {

    @DynamicPropertySource
    static void pgProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestcontainerSupport::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestcontainerSupport::username);
        registry.add("spring.datasource.password", PostgresTestcontainerSupport::password);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired EventPendingRepository repo;
    @Autowired TestEntityManager em;

    @Test
    void save_and_findById_should_roundtrip_with_enums_and_jsonb_payload() {
        EventPendingEntity e = EventPendingTestFixtures.scheduled(
                1L, 99L, EventType.A_GREETING, Instant.parse("2026-05-27T00:00:00Z"));
        repo.save(e);
        em.flush();
        em.clear();

        EventPendingEntity loaded = repo.findById(e.getId()).orElseThrow();
        assertThat(loaded.getEventType()).isEqualTo(EventType.A_GREETING);
        assertThat(loaded.getStatus()).isEqualTo(EventStatus.SCHEDULED);
        assertThat(loaded.getPayload()).isEqualTo("{}");
        assertThat(loaded.getFailCount()).isZero();
        assertThat(loaded.getCreatedAt()).isNotNull();
    }

    @Test
    void findByStatusAndScheduledAtBefore_should_return_only_due_scheduled() {
        Instant now = Instant.parse("2026-05-27T08:00:00Z");
        repo.save(EventPendingTestFixtures.scheduled(1L, 99L, EventType.A_GREETING, now.minusSeconds(60))); // 到期
        repo.save(EventPendingTestFixtures.scheduled(2L, 99L, EventType.B_RECALL, now.plusSeconds(3600)));  // 未到
        em.flush();

        List<EventPendingEntity> due = repo.findByStatusAndScheduledAtBefore(EventStatus.SCHEDULED, now);

        assertThat(due).extracting(EventPendingEntity::getUserId).containsExactly(1L);
    }

    @Test
    void findDueForUpdate_should_return_due_scheduled_with_limit() {
        Instant now = Instant.parse("2026-05-27T08:00:00Z");
        repo.save(EventPendingTestFixtures.scheduled(1L, 99L, EventType.A_GREETING, now.minusSeconds(60)));
        repo.save(EventPendingTestFixtures.scheduled(2L, 99L, EventType.A_GREETING, now.minusSeconds(30)));
        em.flush();

        List<EventPendingEntity> due = repo.findDueForUpdate(now, 10);

        assertThat(due).hasSize(2);
    }
}
