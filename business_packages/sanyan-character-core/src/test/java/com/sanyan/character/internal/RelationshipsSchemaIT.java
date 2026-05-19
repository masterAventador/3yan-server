package com.sanyan.character.internal;

import com.sanyan.common.test.PostgresTestcontainerSupport;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V3 migration 验证：relationships 表（复合 PK + version 乐观锁）。
 */
class RelationshipsSchemaIT extends PostgresTestcontainerSupport {

    @Test
    void v3_should_create_relationships_with_composite_pk_and_version() throws Exception {
        runMigrationsUpTo("3");

        Map<String, ColumnSpec> cols = describeColumns("relationships");

        // 7 列存在
        assertThat(cols).containsKeys("user_id", "character_id", "intimacy_score",
                "current_stage", "version", "created_at", "updated_at");

        // 类型验证（describeColumns 对 BIGINT 无 serial sequence 的列返回 "bigint"）
        assertThat(cols.get("user_id").typeName()).isEqualTo("bigint");
        assertThat(cols.get("character_id").typeName()).isEqualTo("bigint");
        assertThat(cols.get("intimacy_score").typeName()).isEqualTo("int4");
        assertThat(cols.get("current_stage").typeName()).isEqualTo("int2");
        assertThat(cols.get("version").typeName()).isEqualTo("int4");

        // nullability —— 全部 NOT NULL
        assertThat(cols.get("user_id").nullable()).isFalse();
        assertThat(cols.get("character_id").nullable()).isFalse();
        assertThat(cols.get("intimacy_score").nullable()).isFalse();
        assertThat(cols.get("current_stage").nullable()).isFalse();
        assertThat(cols.get("version").nullable()).isFalse();
        assertThat(cols.get("created_at").nullable()).isFalse();
        assertThat(cols.get("updated_at").nullable()).isFalse();
    }
}
