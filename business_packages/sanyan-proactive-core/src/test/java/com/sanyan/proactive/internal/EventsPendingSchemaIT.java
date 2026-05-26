package com.sanyan.proactive.internal;

import com.sanyan.common.test.PostgresTestcontainerSupport;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** V10 migration 验证：events_pending 调度表（event_type/status 大写 enum CHECK + payload jsonb + due/dedup 索引）。 */
class EventsPendingSchemaIT extends PostgresTestcontainerSupport {

    @Test
    void v10_should_create_events_pending_with_enums_jsonb_and_indexes() throws Exception {
        runMigrationsUpTo("10");

        Map<String, ColumnSpec> cols = describeColumns("events_pending");

        assertThat(cols).containsKeys("id", "user_id", "character_id", "event_type", "status",
                "payload", "scheduled_at", "sent_at", "fail_count", "last_error", "created_at");

        assertThat(cols.get("id").typeName()).isEqualTo("bigserial");
        assertThat(cols.get("user_id").typeName()).isEqualTo("bigint");
        assertThat(cols.get("character_id").typeName()).isEqualTo("bigint");
        assertThat(cols.get("event_type").typeName()).isEqualTo("varchar");
        assertThat(cols.get("status").typeName()).isEqualTo("varchar");
        assertThat(cols.get("payload").typeName()).isEqualTo("jsonb");
        assertThat(cols.get("scheduled_at").typeName()).isEqualTo("timestamptz");
        assertThat(cols.get("sent_at").typeName()).isEqualTo("timestamptz");
        assertThat(cols.get("fail_count").typeName()).isEqualTo("int4");
        assertThat(cols.get("last_error").typeName()).isEqualTo("text");
        assertThat(cols.get("created_at").typeName()).isEqualTo("timestamptz");

        assertThat(cols.get("event_type").nullable()).isFalse();
        assertThat(cols.get("status").nullable()).isFalse();
        assertThat(cols.get("payload").nullable()).isFalse();
        assertThat(cols.get("scheduled_at").nullable()).isFalse();
        assertThat(cols.get("fail_count").nullable()).isFalse();
        assertThat(cols.get("sent_at").nullable()).isTrue();
        assertThat(cols.get("last_error").nullable()).isTrue();

        assertThat(indexExists("events_pending", "idx_events_pending_due")).isTrue();
        assertThat(indexExists("events_pending", "idx_events_pending_dedup")).isTrue();

        String checks = checkConstraintsOf("events_pending");
        assertThat(checks).contains("A_GREETING", "B_RECALL", "C_EVENT_FOLLOWUP", "D_EMOTION_CARE");
        assertThat(checks).contains("SCHEDULED", "PROCESSING", "SENT", "FAILED", "CANCELLED");
    }

    private boolean indexExists(String table, String index) throws Exception {
        try (Connection conn = newConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM pg_indexes WHERE tablename=? AND indexname=?")) {
            ps.setString(1, table); ps.setString(2, index);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getInt(1) == 1; }
        }
    }

    private String checkConstraintsOf(String table) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (Connection conn = newConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT pg_get_constraintdef(c.oid) AS def FROM pg_constraint c "
                             + "JOIN pg_class t ON c.conrelid = t.oid WHERE t.relname = ? AND c.contype = 'c'")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) sb.append(rs.getString("def")).append('\n'); }
        }
        return sb.toString();
    }
}
