package com.sanyan.character.internal;

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
 * B1：验证 AiCharacterEntity 新字段 basePrompt + personaConfig (JSONB) 的持久化 roundtrip。
 *
 * <p>必须用 Testcontainers PG（H2 不支持 jsonb）。
 * <p>Schema 由 Flyway V1-V6 完整 migration 生成，ddl-auto=none，与生产链路对齐。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@ContextConfiguration(classes = TestApplication.class)
@EntityScan(basePackages = "com.sanyan.character")
@EnableJpaRepositories(basePackages = "com.sanyan.character")
class AiCharacterEntityFieldIT extends PostgresTestcontainerSupport {

    @DynamicPropertySource
    static void pgProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestcontainerSupport::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestcontainerSupport::username);
        registry.add("spring.datasource.password", PostgresTestcontainerSupport::password);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        // 跑 Flyway 完整 V1-V6 schema，不用 ddl-auto 自动生成
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired
    TestEntityManager em;

    @Test
    void should_persist_base_prompt_and_persona_config_jsonb() {
        AiCharacterEntity ch = AiCharacterTestFixtures.withPersonaConfig(
                "你是测试角色", "{\"stage_overrides\":{\"0\":{}}}");
        // 避免与 V6 seed 里的"小婉"（id=1）冲突，清空 id 让 DB 自增
        ch.setId(null);
        ch.setName("测试角色 B1");

        Long id = em.persistAndFlush(ch).getId();
        em.clear();

        AiCharacterEntity reloaded = em.find(AiCharacterEntity.class, id);
        assertThat(reloaded.getBasePrompt()).isEqualTo("你是测试角色");
        assertThat(reloaded.getPersonaConfig()).contains("stage_overrides");
    }
}
