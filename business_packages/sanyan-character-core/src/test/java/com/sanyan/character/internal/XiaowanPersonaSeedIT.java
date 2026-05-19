package com.sanyan.character.internal;

import com.sanyan.common.test.PostgresTestcontainerSupport;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V6 migration 验证：UPDATE 小婉 base_prompt + persona_config（含 5 阶段 stage_overrides）。
 */
class XiaowanPersonaSeedIT extends PostgresTestcontainerSupport {

    @Test
    void v6_should_seed_xiaowan_persona_with_5_stage_overrides() throws Exception {
        runMigrationsUpTo("6");

        try (Connection conn = newConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT base_prompt, persona_config::text FROM ai_character WHERE name = '小婉'");
             ResultSet rs = ps.executeQuery()) {

            assertThat(rs.next()).as("小婉应该存在").isTrue();

            String basePrompt = rs.getString("base_prompt");
            assertThat(basePrompt).isNotEmpty();
            assertThat(basePrompt).contains("小婉");

            String personaJson = rs.getString("persona_config");
            assertThat(personaJson).contains("stage_overrides");
            // 5 个 stage key 全部存在
            for (int i = 0; i <= 4; i++) {
                assertThat(personaJson)
                        .as("stage_overrides 应含 stage %d", i)
                        .contains("\"" + i + "\"");
            }
            // 关键阶段称呼词
            assertThat(personaJson).contains("笨蛋");   // stage 2
            assertThat(personaJson).contains("宝贝");   // stage 3
            assertThat(personaJson).contains("老公");   // stage 4
        }
    }
}
