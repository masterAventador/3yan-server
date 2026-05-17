package com.sanyan.character.internal.intimacy;

import com.sanyan.common.test.PostgresTestcontainerSupport;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V4 migration 验证：intimacy_logs 审计表 + 时间倒序索引。
 */
class IntimacyLogsSchemaIT extends PostgresTestcontainerSupport {

    @Test
    void v4_should_create_intimacy_logs_with_descending_index() throws Exception {
        runMigrationsUpTo("4");

        Map<String, ColumnSpec> cols = describeColumns("intimacy_logs");

        // 9 列存在
        assertThat(cols).containsKeys("id", "user_id", "character_id", "delta",
                "reason", "metadata", "new_score", "new_stage", "created_at");

        // 类型
        assertThat(cols.get("id").typeName()).isEqualTo("bigserial");
        assertThat(cols.get("user_id").typeName()).isEqualTo("bigint");
        assertThat(cols.get("character_id").typeName()).isEqualTo("bigint");
        assertThat(cols.get("delta").typeName()).isEqualTo("int4");
        assertThat(cols.get("reason").typeName()).isEqualTo("varchar");
        assertThat(cols.get("metadata").typeName()).isEqualTo("jsonb");
        assertThat(cols.get("new_score").typeName()).isEqualTo("int4");
        assertThat(cols.get("new_stage").typeName()).isEqualTo("int2");
        assertThat(cols.get("created_at").typeName()).isEqualTo("timestamp");

        // nullability（注意 metadata 是唯一可空列）
        assertThat(cols.get("id").nullable()).isFalse();
        assertThat(cols.get("user_id").nullable()).isFalse();
        assertThat(cols.get("character_id").nullable()).isFalse();
        assertThat(cols.get("delta").nullable()).isFalse();
        assertThat(cols.get("reason").nullable()).isFalse();
        assertThat(cols.get("metadata").nullable()).isTrue();
        assertThat(cols.get("new_score").nullable()).isFalse();
        assertThat(cols.get("new_stage").nullable()).isFalse();
        assertThat(cols.get("created_at").nullable()).isFalse();

        // 索引存在性（user_id + character_id + created_at DESC）
        try (Connection conn = newConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM pg_indexes WHERE tablename='intimacy_logs' " +
                     "AND indexname='idx_intimacy_logs_user_char_time'");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }
}
