package com.sanyan.character.internal;

import com.sanyan.common.test.PostgresTestcontainerSupport;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V2 migration 验证：ai_character 表加 base_prompt + persona_config 列。
 * 用 Testcontainers PG（H2 不支持 jsonb）。
 */
class AiCharacterPersonaSchemaIT extends PostgresTestcontainerSupport {

    @Test
    void v2_should_add_base_prompt_and_persona_config_columns() throws Exception {
        runMigrationsUpTo("2");

        Map<String, ColumnSpec> cols = describeColumns("ai_character");

        assertThat(cols).containsKey("base_prompt");
        assertThat(cols).containsKey("persona_config");

        assertThat(cols.get("base_prompt").typeName()).isEqualTo("text");
        assertThat(cols.get("base_prompt").nullable()).isFalse();

        assertThat(cols.get("persona_config").typeName()).isEqualTo("jsonb");
        assertThat(cols.get("persona_config").nullable()).isFalse();
    }
}
