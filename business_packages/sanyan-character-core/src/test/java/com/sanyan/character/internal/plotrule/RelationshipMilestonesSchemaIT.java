package com.sanyan.character.internal.plotrule;

import com.sanyan.common.test.PostgresTestcontainerSupport;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V5 migration 验证：relationship_milestones 表（3 列复合 PK 去重）。
 */
class RelationshipMilestonesSchemaIT extends PostgresTestcontainerSupport {

    @Test
    void v5_should_create_milestones_with_3col_pk() throws Exception {
        runMigrationsUpTo("5");

        Map<String, ColumnSpec> cols = describeColumns("relationship_milestones");

        assertThat(cols).containsKeys("user_id", "character_id", "rule_id", "triggered_at");

        assertThat(cols.get("user_id").typeName()).isEqualTo("bigint");
        assertThat(cols.get("character_id").typeName()).isEqualTo("bigint");
        assertThat(cols.get("rule_id").typeName()).isEqualTo("varchar");
        assertThat(cols.get("triggered_at").typeName()).isEqualTo("timestamp");

        // 全部 NOT NULL
        assertThat(cols.get("user_id").nullable()).isFalse();
        assertThat(cols.get("character_id").nullable()).isFalse();
        assertThat(cols.get("rule_id").nullable()).isFalse();
        assertThat(cols.get("triggered_at").nullable()).isFalse();

        // 3 列复合 PK + 顺序
        List<String> pk = new ArrayList<>();
        try (Connection conn = newConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT a.attname FROM pg_index i " +
                 "JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey) " +
                 "WHERE i.indrelid = 'relationship_milestones'::regclass AND i.indisprimary " +
                 "ORDER BY array_position(i.indkey, a.attnum)");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) pk.add(rs.getString(1));
        }
        assertThat(pk).containsExactly("user_id", "character_id", "rule_id");
    }
}
