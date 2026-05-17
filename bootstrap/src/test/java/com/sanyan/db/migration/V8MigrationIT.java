package com.sanyan.db.migration;

import com.sanyan.common.test.PostgresTestcontainerSupport;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan 2.5：验证 V8__memory_profile_free_form.sql 迁移把 memory_profiles 改成自然语言画像。
 *
 * <p>测试在共享 PG Testcontainer 上跑全量 Flyway migration（V1 → V8），然后断言
 * memory_profiles 表：
 * <ul>
 *   <li>{@code profile_jsonb} 列已被删除</li>
 *   <li>{@code summary_text} TEXT NOT NULL 列已新增（DEFAULT ''）</li>
 *   <li>复合主键 (user_id, character_id) 仍然有效</li>
 *   <li>{@code version} / {@code updated_at} 列保持原样</li>
 *   <li>插入最小行（不带 summary_text）后 {@code summary_text} 默认为 {@code ''}</li>
 * </ul>
 *
 * <p>每个 @Test 在 {@link PostgresTestcontainerSupport#runMigrationsUpTo(String)}
 * 里先 {@code Flyway.clean()} 再 migrate，保证用例互相隔离。
 */
class V8MigrationIT extends PostgresTestcontainerSupport {

    @Test
    void migration_replacesProfileJsonbWithSummaryText() throws Exception {
        runMigrationsUpTo("8");

        Map<String, ColumnSpec> cols = describeColumns("memory_profiles");

        // 新列 + 旧列们
        assertThat(cols).containsKeys(
                "user_id",
                "character_id",
                "summary_text",
                "version",
                "updated_at"
        );
        assertThat(cols)
                .as("V8 必须删掉 profile_jsonb 列")
                .doesNotContainKey("profile_jsonb");

        assertThat(cols.get("user_id").nullable()).as("user_id NOT NULL").isFalse();
        assertThat(cols.get("character_id").nullable()).as("character_id NOT NULL").isFalse();
        assertThat(cols.get("summary_text").nullable()).as("summary_text NOT NULL").isFalse();
        assertThat(cols.get("version").nullable()).as("version NOT NULL").isFalse();
        assertThat(cols.get("updated_at").nullable()).as("updated_at NOT NULL").isFalse();

        assertThat(cols.get("user_id").typeName()).isEqualToIgnoringCase("bigint");
        assertThat(cols.get("character_id").typeName()).isEqualToIgnoringCase("bigint");
        assertThat(cols.get("summary_text").typeName()).isEqualToIgnoringCase("text");
        assertThat(cols.get("version").typeName()).isEqualToIgnoringCase("int4");
        assertThat(cols.get("updated_at").typeName()).isEqualToIgnoringCase("timestamp");
    }

    @Test
    void summaryText_defaultsToEmptyStringOnMinimalInsert() throws Exception {
        runMigrationsUpTo("8");

        try (Connection conn = newConnection();
             Statement st = conn.createStatement()) {
            st.executeUpdate(
                    "INSERT INTO memory_profiles (user_id, character_id) VALUES (10, 100)");

            try (ResultSet rs = st.executeQuery(
                    "SELECT summary_text, version, updated_at "
                            + "FROM memory_profiles WHERE user_id = 10 AND character_id = 100")) {
                assertThat(rs.next()).as("应能查到刚插入的行").isTrue();
                assertThat(rs.getString("summary_text"))
                        .as("summary_text 默认 ''")
                        .isEqualTo("");
                assertThat(rs.getInt("version")).as("version 默认 1").isEqualTo(1);
                assertThat(rs.getTimestamp("updated_at"))
                        .as("updated_at 默认 NOW()，非 null")
                        .isNotNull();
            }
        }
    }

    @Test
    void summaryText_supportsLongFreeFormText() throws Exception {
        runMigrationsUpTo("8");

        String longSummary = "用户名叫张三，前端工程师，住北京。家里养了一只叫橘子的美短猫，"
                + "最近因为面试焦虑。喜欢日料，特别是寿司。😺🍣";

        try (Connection conn = newConnection();
             Statement st = conn.createStatement()) {
            st.executeUpdate(
                    "INSERT INTO memory_profiles (user_id, character_id, summary_text) "
                            + "VALUES (11, 100, '" + longSummary.replace("'", "''") + "')");

            try (ResultSet rs = st.executeQuery(
                    "SELECT summary_text FROM memory_profiles WHERE user_id = 11 AND character_id = 100")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("summary_text"))
                        .as("TEXT 列应原样保留长段落 + Unicode")
                        .isEqualTo(longSummary);
            }
        }
    }
}
