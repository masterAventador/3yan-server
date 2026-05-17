package com.sanyan.character.internal.intimacy;

import com.sanyan.character.internal.intimacy.fixtures.IntimacyLogTestFixtures;
import com.sanyan.common.test.PostgresTestcontainerSupport;
import com.sanyan.common.test.TestApplication;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * B3：IntimacyLogRepository 派生查询 findTop10...DESC 验证。
 *
 * <p>intimacy_logs 无 FK 约束（审计表故意解耦），所以不需要先 seed users。
 * Schema 由 Flyway V1-V6 完整 migration 生成，ddl-auto=none，与生产链路对齐。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@ContextConfiguration(classes = TestApplication.class)
@EntityScan(basePackages = "com.sanyan.character")
@EnableJpaRepositories(basePackages = "com.sanyan.character")
class IntimacyLogRepositoryIT extends PostgresTestcontainerSupport {

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
    IntimacyLogRepository repo;

    @Test
    void findTop10_should_return_descending_by_created_at() {
        for (int i = 0; i < 15; i++) {
            repo.save(IntimacyLogTestFixtures.validLog(1L, 1L, "MESSAGE_SENT", 1, 1 + i, 0));
        }
        List<IntimacyLogEntity> logs =
                repo.findTop10ByUserIdAndCharacterIdOrderByCreatedAtDesc(1L, 1L);
        assertThat(logs).hasSize(10);
        assertThat(logs.get(0).getNewScore()).isGreaterThan(logs.get(9).getNewScore());
    }
}
