package com.sanyan.character.internal;

import com.sanyan.common.test.PostgresTestcontainerSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V2 migration 验证：ai_character 表加 base_prompt + persona_config 列。
 *
 * <p>用 Testcontainers PG 而非 H2，原因：
 * <ul>
 *   <li>persona_config 是 JSONB，H2 不支持该类型</li>
 *   <li>生产是 PG 17，需验证 migration 在真实 PG 上执行正确</li>
 * </ul>
 *
 * <p>本测试在 sanyan-character-core 模块中，无法通过 {@code classpath:db/migration}
 * 访问 bootstrap 模块的迁移文件，因此通过 {@code filesystem:} 前缀使用绝对路径定位。
 * 路径通过 Maven 属性（{@code db.migration.dir}）从父 pom 注入，保持可移植性。
 * 若未设置该属性，回退到相对路径（仅适用于从 server 目录运行的情况）。
 */
class AiCharacterPersonaSchemaIT extends PostgresTestcontainerSupport {

    @Test
    void v2_should_add_base_prompt_and_persona_config_columns() throws Exception {
        // 通过 Flyway 跑到 V2（使用文件系统路径，不依赖 classpath）
        String migrationDir = System.getProperty("db.migration.dir",
                "bootstrap/src/main/resources/db/migration");
        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl(), username(), password())
                .locations("filesystem:" + migrationDir)
                .target("2")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();

        Map<String, ColumnSpec> cols = describeColumns("ai_character");

        assertThat(cols).containsKey("base_prompt");
        assertThat(cols).containsKey("persona_config");

        ColumnSpec basePrompt = cols.get("base_prompt");
        assertThat(basePrompt.typeName()).as("base_prompt 应为 text 类型").isEqualTo("text");
        assertThat(basePrompt.nullable()).as("base_prompt 应 NOT NULL").isFalse();

        ColumnSpec personaConfig = cols.get("persona_config");
        assertThat(personaConfig.typeName()).as("persona_config 应为 jsonb 类型").isEqualTo("jsonb");
        assertThat(personaConfig.nullable()).as("persona_config 应 NOT NULL").isFalse();
    }
}
