package com.sanyan.character.internal.plotrule;

import com.sanyan.character.internal.plotrule.fixtures.RelationshipMilestoneTestFixtures;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * B4：RelationshipMilestoneRepository existsById 验证（3 列复合 PK 去重）。
 *
 * <p>relationship_milestones 无额外 FK 约束需要预 seed，
 * 但 V5 表有 FK 指向 relationships，而 relationships 有 FK 到 users 和 ai_characters。
 * 测试使用真实 Flyway 迁移（V1-V6）完整建表，不依赖 ddl-auto。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@ContextConfiguration(classes = TestApplication.class)
@EntityScan(basePackages = "com.sanyan.character")
@EnableJpaRepositories(basePackages = "com.sanyan.character")
class RelationshipMilestoneRepositoryIT extends PostgresTestcontainerSupport {

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
    RelationshipMilestoneRepository repo;

    @Test
    void existsBy_should_be_true_only_after_save() {
        var id = new RelationshipMilestoneId(1L, 1L, "deep_night_chat");
        assertThat(repo.existsById(id)).isFalse();

        repo.save(RelationshipMilestoneTestFixtures.validMilestone(1L, 1L, "deep_night_chat"));

        assertThat(repo.existsById(id)).isTrue();
    }
}
