package com.sanyan.db.migration;

import com.sanyan.common.test.PostgresTestcontainerSupport;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 V5__init_memory_summaries.sql 迁移产生预期 schema。
 *
 * <p>测试在共享 PG Testcontainer 上跑全量 Flyway migration（V1 → V5），然后断言
 * memory_summaries 表的字段类型 / nullability / 主键 / 索引都符合 plan 规范。
 *
 * <p>每个 @Test 在 {@link PostgresTestcontainerSupport#runMigrationsUpTo(String)}
 * 里先 {@code Flyway.clean()} 再 migrate，保证用例之间互相隔离，不依赖执行顺序。
 */
class V5MigrationIT extends PostgresTestcontainerSupport {

    @Test
    void migration_createsMemorySummariesTable_withExpectedColumns() throws Exception {
        runMigrationsUpTo("5");

        Map<String, ColumnSpec> cols = describeColumns("memory_summaries");

        assertThat(cols).containsKeys(
                "id",
                "user_id",
                "character_id",
                "period_start_message_id",
                "period_end_message_id",
                "summary_text",
                "message_count",
                "created_at"
        );

        assertThat(cols.get("id").nullable()).as("id NOT NULL").isFalse();
        assertThat(cols.get("user_id").nullable()).as("user_id NOT NULL").isFalse();
        assertThat(cols.get("character_id").nullable()).as("character_id NOT NULL").isFalse();
        assertThat(cols.get("period_start_message_id").nullable()).as("period_start_message_id nullable").isTrue();
        assertThat(cols.get("period_end_message_id").nullable()).as("period_end_message_id nullable").isTrue();
        assertThat(cols.get("summary_text").nullable()).as("summary_text NOT NULL").isFalse();
        assertThat(cols.get("message_count").nullable()).as("message_count NOT NULL").isFalse();
        assertThat(cols.get("created_at").nullable()).as("created_at NOT NULL").isFalse();

        // id 是 BIGSERIAL（PG 自动建 sequence，类型是 bigint），其他 long 字段也是 bigint
        assertThat(cols.get("id").typeName()).isEqualToIgnoringCase("bigserial");
        assertThat(cols.get("user_id").typeName()).isEqualToIgnoringCase("bigint");
        assertThat(cols.get("character_id").typeName()).isEqualToIgnoringCase("bigint");
        assertThat(cols.get("period_start_message_id").typeName()).isEqualToIgnoringCase("bigint");
        assertThat(cols.get("period_end_message_id").typeName()).isEqualToIgnoringCase("bigint");
        assertThat(cols.get("summary_text").typeName()).isEqualToIgnoringCase("text");
        assertThat(cols.get("message_count").typeName()).isEqualToIgnoringCase("int4");
        assertThat(cols.get("created_at").typeName()).isEqualToIgnoringCase("timestamp");
    }

    @Test
    void migration_createsPrimaryKeyOnId() throws Exception {
        runMigrationsUpTo("5");

        try (Connection conn = newConnection()) {
            DatabaseMetaData md = conn.getMetaData();
            try (ResultSet rs = md.getPrimaryKeys(null, "public", "memory_summaries")) {
                assertThat(rs.next()).as("memory_summaries 必须有主键").isTrue();
                assertThat(rs.getString("COLUMN_NAME")).isEqualToIgnoringCase("id");
                assertThat(rs.next()).as("主键应只含 id 一列").isFalse();
            }
        }
    }

    @Test
    void migration_createsCompositeIndexOnUserCharacterCreatedAt() throws Exception {
        runMigrationsUpTo("5");

        // PG 的 pg_indexes 视图可直接拿到 indexdef，比 JDBC metadata 更准确（含 DESC 信息）
        try (Connection conn = newConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT indexdef FROM pg_indexes "
                             + "WHERE schemaname = 'public' AND tablename = 'memory_summaries' "
                             + "AND indexname = 'idx_memory_summaries_user_char_created'")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("索引 idx_memory_summaries_user_char_created 必须存在").isTrue();
                String def = rs.getString(1).toLowerCase();
                assertThat(def).contains("user_id");
                assertThat(def).contains("character_id");
                assertThat(def).contains("created_at desc");
            }
        }
    }
}
