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

        // UNIQUE (user_id, platform, vendor, token) —— PG 自动建同名唯一索引，
        // 索引名内含四列名 + 顺序，验证它存在即等于验证 UNIQUE 恰好覆盖这四列组合
        // （漏写任一列索引名就会变化，断言随之失败，避免"任意 UNIQUE 都算过"的弱断言）。
        assertThat(indexExists("device_tokens",
                "device_tokens_user_id_platform_vendor_token_key")).isTrue();
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
