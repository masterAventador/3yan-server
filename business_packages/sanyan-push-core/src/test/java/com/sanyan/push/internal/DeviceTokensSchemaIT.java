package com.sanyan.push.internal;

import com.sanyan.common.test.PostgresTestcontainerSupport;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** V11 migration 验证：device_tokens 表（platform 小写 CHECK 'ios'/'android' + 四列 UNIQUE）。 */
class DeviceTokensSchemaIT extends PostgresTestcontainerSupport {

    @Test
    void v11_should_create_device_tokens_with_unique_and_platform_check() throws Exception {
        runMigrationsUpTo("11");

        Map<String, ColumnSpec> cols = describeColumns("device_tokens");

        assertThat(cols).containsKeys("id", "user_id", "platform", "vendor",
                "token", "active", "registered_at", "last_seen");

        assertThat(cols.get("id").typeName()).isEqualTo("bigserial");
        assertThat(cols.get("user_id").typeName()).isEqualTo("bigint");
        assertThat(cols.get("platform").typeName()).isEqualTo("varchar");
        assertThat(cols.get("vendor").typeName()).isEqualTo("varchar");
        assertThat(cols.get("token").typeName()).isEqualTo("varchar");
        assertThat(cols.get("active").typeName()).isEqualTo("bool");
        assertThat(cols.get("registered_at").typeName()).isEqualTo("timestamptz");
        assertThat(cols.get("last_seen").typeName()).isEqualTo("timestamptz");

        assertThat(cols.get("user_id").nullable()).isFalse();
        assertThat(cols.get("platform").nullable()).isFalse();
        assertThat(cols.get("vendor").nullable()).isFalse();
        assertThat(cols.get("token").nullable()).isFalse();
        assertThat(cols.get("active").nullable()).isFalse();
        assertThat(cols.get("registered_at").nullable()).isFalse();
        assertThat(cols.get("last_seen").nullable()).isFalse();

        String checks = checkConstraintsOf("device_tokens");
        assertThat(checks).contains("ios", "android");

        assertThat(uniqueConstraintExists("device_tokens")).isTrue();
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

    private boolean uniqueConstraintExists(String table) throws Exception {
        try (Connection conn = newConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM pg_constraint c JOIN pg_class t ON c.conrelid = t.oid "
                             + "WHERE t.relname = ? AND c.contype = 'u'")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getInt(1) >= 1; }
        }
    }
}
