package com.sanyan.db.migration;

import com.sanyan.common.test.PostgresTestcontainerSupport;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证 V6__init_memory_profiles.sql 迁移产生预期 schema。
 *
 * <p>测试在共享 PG Testcontainer 上跑全量 Flyway migration（V1 → V6），然后断言
 * memory_profiles 表：
 * <ul>
 *   <li>字段类型 / nullability 符合 plan §L2 spec</li>
 *   <li>复合主键 {@code (user_id, character_id)} 生效（重复插入相同 PK 抛错）</li>
 *   <li>{@code profile_jsonb} 默认值 {@code '{}'} + JSONB 字段可读写往返</li>
 *   <li>{@code version} 默认 1，{@code updated_at} 默认 NOW()</li>
 * </ul>
 *
 * <p>每个 @Test 在 {@link PostgresTestcontainerSupport#runMigrationsUpTo(String)}
 * 里先 {@code Flyway.clean()} 再 migrate，保证用例之间互相隔离。
 */
class V6MigrationIT extends PostgresTestcontainerSupport {

    @Test
    void migration_createsMemoryProfilesTable_withExpectedColumns() throws Exception {
        runMigrationsUpTo("6");

        Map<String, ColumnSpec> cols = describeColumns("memory_profiles");

        assertThat(cols).containsKeys(
                "user_id",
                "character_id",
                "profile_jsonb",
                "version",
                "updated_at"
        );

        assertThat(cols.get("user_id").nullable()).as("user_id NOT NULL").isFalse();
        assertThat(cols.get("character_id").nullable()).as("character_id NOT NULL").isFalse();
        assertThat(cols.get("profile_jsonb").nullable()).as("profile_jsonb NOT NULL").isFalse();
        assertThat(cols.get("version").nullable()).as("version NOT NULL").isFalse();
        assertThat(cols.get("updated_at").nullable()).as("updated_at NOT NULL").isFalse();

        assertThat(cols.get("user_id").typeName()).isEqualToIgnoringCase("bigint");
        assertThat(cols.get("character_id").typeName()).isEqualToIgnoringCase("bigint");
        assertThat(cols.get("profile_jsonb").typeName()).isEqualToIgnoringCase("jsonb");
        assertThat(cols.get("version").typeName()).isEqualToIgnoringCase("int4");
        assertThat(cols.get("updated_at").typeName()).isEqualToIgnoringCase("timestamp");
    }

    @Test
    void migration_createsCompositePrimaryKey_onUserIdAndCharacterId() throws Exception {
        runMigrationsUpTo("6");

        try (Connection conn = newConnection()) {
            DatabaseMetaData md = conn.getMetaData();
            List<String> pkColumns = new ArrayList<>();
            try (ResultSet rs = md.getPrimaryKeys(null, "public", "memory_profiles")) {
                while (rs.next()) {
                    pkColumns.add(rs.getString("COLUMN_NAME").toLowerCase());
                }
            }
            assertThat(pkColumns)
                    .as("memory_profiles 主键应为 (user_id, character_id) 复合主键")
                    .containsExactlyInAnyOrder("user_id", "character_id")
                    .hasSize(2);
        }
    }

    @Test
    void compositePrimaryKey_rejectsDuplicateUserCharacterPair() throws Exception {
        runMigrationsUpTo("6");

        try (Connection conn = newConnection();
             Statement st = conn.createStatement()) {
            st.executeUpdate(
                    "INSERT INTO memory_profiles (user_id, character_id) VALUES (1, 100)");

            assertThatThrownBy(() ->
                    st.executeUpdate(
                            "INSERT INTO memory_profiles (user_id, character_id) VALUES (1, 100)"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("duplicate key");
        }
    }

    @Test
    void defaultValues_areApplied_onMinimalInsert() throws Exception {
        runMigrationsUpTo("6");

        try (Connection conn = newConnection();
             Statement st = conn.createStatement()) {
            st.executeUpdate(
                    "INSERT INTO memory_profiles (user_id, character_id) VALUES (2, 200)");

            try (ResultSet rs = st.executeQuery(
                    "SELECT profile_jsonb::text AS pj, version, updated_at "
                            + "FROM memory_profiles WHERE user_id = 2 AND character_id = 200")) {
                assertThat(rs.next()).as("应能查到刚插入的行").isTrue();
                assertThat(rs.getString("pj")).as("profile_jsonb 默认 '{}'").isEqualTo("{}");
                assertThat(rs.getInt("version")).as("version 默认 1").isEqualTo(1);
                assertThat(rs.getTimestamp("updated_at")).as("updated_at 默认 NOW()，非 null").isNotNull();
            }
        }
    }

    @Test
    void jsonbField_supportsReadWriteRoundTrip_withNestedStructure() throws Exception {
        runMigrationsUpTo("6");

        // 应用层 O1 task 会插入完整 JSONB 结构（basic_info / preferences / events / emotion_line）
        // 本测试用一个能覆盖嵌套对象 + 数组 + null 的样本验证 PG JSONB 类型读写完整保真
        String jsonPayload = """
                {
                  "basic_info": { "name": "小明", "age": null, "occupation": "工程师", "hometown": null },
                  "preferences": { "foods": ["火锅", "寿司"], "movies": [], "colors": ["蓝"], "music": [] },
                  "events": [{"id": 1, "desc": "第一次见面"}],
                  "emotion_line": []
                }
                """;

        try (Connection conn = newConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO memory_profiles (user_id, character_id, profile_jsonb) "
                             + "VALUES (?, ?, ?::jsonb)")) {
            ps.setLong(1, 3L);
            ps.setLong(2, 300L);
            ps.setString(3, jsonPayload);
            ps.executeUpdate();
        }

        try (Connection conn = newConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT profile_jsonb->'basic_info'->>'name' AS name, "
                             + "       profile_jsonb->'basic_info'->>'age' AS age, "
                             + "       jsonb_array_length(profile_jsonb->'preferences'->'foods') AS food_count, "
                             + "       jsonb_array_length(profile_jsonb->'events') AS event_count "
                             + "FROM memory_profiles WHERE user_id = 3 AND character_id = 300")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("JSONB 行应可查到").isTrue();
                assertThat(rs.getString("name")).isEqualTo("小明");
                assertThat(rs.getString("age")).as("age 在 JSON 里是 null，对应 SQL null").isNull();
                assertThat(rs.getInt("food_count")).isEqualTo(2);
                assertThat(rs.getInt("event_count")).isEqualTo(1);
            }
        }
    }
}
