package com.sanyan.memory.internal.item;

import com.sanyan.common.test.PostgresTestcontainerSupport;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** V9 migration 验证：memory_item 结构化记忆表（kind / status 大写 enum CHECK + 双索引）。 */
class MemoryItemSchemaIT extends PostgresTestcontainerSupport {

    @Test
    void v9_should_create_memory_item_with_kind_status_and_indexes() throws Exception {
        runMigrationsUpTo("9");

        Map<String, ColumnSpec> cols = describeColumns("memory_item");

        assertThat(cols).containsKeys("id", "user_id", "character_id", "kind", "content",
                "salient_at", "status", "source_message_id", "created_at");

        assertThat(cols.get("id").typeName()).isEqualTo("bigserial");
        assertThat(cols.get("user_id").typeName()).isEqualTo("bigint");
        assertThat(cols.get("character_id").typeName()).isEqualTo("bigint");
        assertThat(cols.get("kind").typeName()).isEqualTo("varchar");
        assertThat(cols.get("content").typeName()).isEqualTo("text");
        assertThat(cols.get("salient_at").typeName()).isEqualTo("timestamptz");
        assertThat(cols.get("status").typeName()).isEqualTo("varchar");
        assertThat(cols.get("source_message_id").typeName()).isEqualTo("bigint");
        assertThat(cols.get("created_at").typeName()).isEqualTo("timestamptz");

        assertThat(cols.get("id").nullable()).isFalse();
        assertThat(cols.get("user_id").nullable()).isFalse();
        assertThat(cols.get("character_id").nullable()).isFalse();
        assertThat(cols.get("kind").nullable()).isFalse();
        assertThat(cols.get("content").nullable()).isFalse();
        assertThat(cols.get("salient_at").nullable()).isFalse();
        assertThat(cols.get("status").nullable()).isFalse();
        assertThat(cols.get("source_message_id").nullable()).isTrue();
        assertThat(cols.get("created_at").nullable()).isFalse();

        assertThat(indexExists("memory_item", "idx_memory_item_salient")).isTrue();
        assertThat(indexExists("memory_item", "idx_memory_item_user")).isTrue();

        String checkClause = checkConstraintsOf("memory_item");
        assertThat(checkClause).contains("PLAN_EVENT", "EMOTION", "PROMISE");
        assertThat(checkClause).contains("PENDING", "DONE", "EXPIRED");
    }

    private boolean indexExists(String table, String index) throws Exception {
        try (Connection conn = newConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM pg_indexes WHERE tablename=? AND indexname=?")) {
            ps.setString(1, table);
            ps.setString(2, index);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) == 1;
            }
        }
    }

    private String checkConstraintsOf(String table) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (Connection conn = newConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT pg_get_constraintdef(c.oid) AS def "
                             + "FROM pg_constraint c JOIN pg_class t ON c.conrelid = t.oid "
                             + "WHERE t.relname = ? AND c.contype = 'c'")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) sb.append(rs.getString("def")).append('\n');
            }
        }
        return sb.toString();
    }
}
