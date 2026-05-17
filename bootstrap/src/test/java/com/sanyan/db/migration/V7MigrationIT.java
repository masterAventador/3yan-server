package com.sanyan.db.migration;

import com.sanyan.common.test.PostgresTestcontainerSupport;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 V7__init_chat_embeddings.sql 迁移产生预期 schema + pgvector 扩展启用。
 *
 * <p>测试在共享 PG Testcontainer（{@code pgvector/pgvector:pg17}）上跑 Flyway
 * migration（V1 → V7），然后断言：
 * <ul>
 *   <li>{@code vector} 扩展加载成功（version 非空）</li>
 *   <li>{@code chat_embeddings} 表存在，字段类型 / nullability 符合 plan §L3 spec</li>
 *   <li>主键在 {@code id} 上</li>
 *   <li>{@code ivfflat} 索引存在，使用 {@code vector_cosine_ops} + {@code lists = 100}</li>
 *   <li>{@code embedding vector(1024)} 字段支持插入 + 读出 1024 维向量（用字符串
 *       {@code '[v1,v2,...]'::vector} 走纯 JDBC，本 task 不引入 pgvector Java SDK，
 *       那是 P1 task 的事）</li>
 * </ul>
 *
 * <p>{@code pgvector/pgvector:pg17} 镜像装了 pgvector 系统库但 PG 启动后**不会**自动
 * {@code CREATE EXTENSION}，这正是验证 V7 migration 自己负责 {@code CREATE EXTENSION
 * IF NOT EXISTS vector} 的正确状态。
 *
 * <p>每个 @Test 在 {@link PostgresTestcontainerSupport#runMigrationsUpTo(String)}
 * 里先 {@code Flyway.clean()} 再 migrate，保证用例之间互相隔离。
 */
class V7MigrationIT extends PostgresTestcontainerSupport {

    @Test
    void migration_enablesPgvectorExtension() throws Exception {
        runMigrationsUpTo("7");

        try (Connection conn = newConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT extversion FROM pg_extension WHERE extname = 'vector'")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("vector 扩展必须被 V7 migration 加载").isTrue();
                String version = rs.getString(1);
                assertThat(version).as("pgvector extversion 非空").isNotBlank();
            }
        }
    }

    @Test
    void migration_createsChatEmbeddingsTable_withExpectedColumns() throws Exception {
        runMigrationsUpTo("7");

        Map<String, ColumnSpec> cols = describeColumns("chat_embeddings");

        assertThat(cols).containsKeys(
                "id",
                "user_id",
                "character_id",
                "message_ids",
                "chunk_text",
                "embedding",
                "token_count",
                "created_at"
        );

        assertThat(cols.get("id").nullable()).as("id NOT NULL").isFalse();
        assertThat(cols.get("user_id").nullable()).as("user_id NOT NULL").isFalse();
        assertThat(cols.get("character_id").nullable()).as("character_id NOT NULL").isFalse();
        assertThat(cols.get("message_ids").nullable()).as("message_ids NOT NULL").isFalse();
        assertThat(cols.get("chunk_text").nullable()).as("chunk_text NOT NULL").isFalse();
        assertThat(cols.get("embedding").nullable()).as("embedding NOT NULL").isFalse();
        assertThat(cols.get("token_count").nullable()).as("token_count nullable").isTrue();
        assertThat(cols.get("created_at").nullable()).as("created_at NOT NULL").isFalse();

        // BIGSERIAL → describeColumns 识别为 bigserial；其他 long 字段为 bigint
        assertThat(cols.get("id").typeName()).isEqualToIgnoringCase("bigserial");
        assertThat(cols.get("user_id").typeName()).isEqualToIgnoringCase("bigint");
        assertThat(cols.get("character_id").typeName()).isEqualToIgnoringCase("bigint");
        // BIGINT[] 在 information_schema.columns.udt_name 里是 "_int8"（PG 数组元素类型前加下划线）
        assertThat(cols.get("message_ids").typeName()).isEqualToIgnoringCase("_int8");
        assertThat(cols.get("chunk_text").typeName()).isEqualToIgnoringCase("text");
        // vector 是 pgvector 自定义类型，udt_name = "vector"
        assertThat(cols.get("embedding").typeName()).isEqualToIgnoringCase("vector");
        assertThat(cols.get("token_count").typeName()).isEqualToIgnoringCase("int4");
        assertThat(cols.get("created_at").typeName()).isEqualToIgnoringCase("timestamp");
    }

    @Test
    void migration_createsPrimaryKeyOnId() throws Exception {
        runMigrationsUpTo("7");

        try (Connection conn = newConnection()) {
            DatabaseMetaData md = conn.getMetaData();
            try (ResultSet rs = md.getPrimaryKeys(null, "public", "chat_embeddings")) {
                assertThat(rs.next()).as("chat_embeddings 必须有主键").isTrue();
                assertThat(rs.getString("COLUMN_NAME")).isEqualToIgnoringCase("id");
                assertThat(rs.next()).as("主键应只含 id 一列").isFalse();
            }
        }
    }

    @Test
    void migration_createsIvfflatIndex_withCosineOpsAndLists100() throws Exception {
        runMigrationsUpTo("7");

        // pg_indexes.indexdef 是创建语句的反编译文本；ivfflat 关键词 + 操作类 + lists 参数都会被保留
        try (Connection conn = newConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT indexdef FROM pg_indexes "
                             + "WHERE schemaname = 'public' AND tablename = 'chat_embeddings'")) {
            try (ResultSet rs = ps.executeQuery()) {
                String ivfflatDef = null;
                while (rs.next()) {
                    String def = rs.getString(1);
                    if (def != null && def.toLowerCase().contains("ivfflat")) {
                        ivfflatDef = def.toLowerCase();
                        break;
                    }
                }
                assertThat(ivfflatDef)
                        .as("chat_embeddings 必须有 ivfflat 索引")
                        .isNotNull();
                assertThat(ivfflatDef).contains("embedding");
                assertThat(ivfflatDef).contains("vector_cosine_ops");
                assertThat(ivfflatDef).contains("lists");
                assertThat(ivfflatDef).contains("100");
            }
        }
    }

    @Test
    void embeddingColumn_supportsVector1024_insertAndRead() throws Exception {
        runMigrationsUpTo("7");

        // 用纯 JDBC + 字符串 '[v1,v2,...]'::vector 走通插入/读取
        // 本 task 不引入 com.pgvector:pgvector-java SDK（那是 P1 task 的事，要配套 Hibernate UserType）
        StringBuilder vec = new StringBuilder("[");
        for (int i = 0; i < 1024; i++) {
            if (i > 0) vec.append(',');
            // 用 i / 1024.0 生成 0~1 之间的浮点值，覆盖整段维度
            vec.append(String.format(java.util.Locale.ROOT, "%.6f", i / 1024.0));
        }
        vec.append(']');
        String vecLiteral = vec.toString();

        try (Connection conn = newConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO chat_embeddings "
                             + "(user_id, character_id, message_ids, chunk_text, embedding, token_count) "
                             + "VALUES (?, ?, ?, ?, ?::vector, ?)")) {
            ps.setLong(1, 42L);
            ps.setLong(2, 7L);
            ps.setArray(3, conn.createArrayOf("bigint", new Long[]{1001L, 1002L, 1003L}));
            ps.setString(4, "测试切片：今天天气真好");
            ps.setString(5, vecLiteral);
            ps.setInt(6, 12);
            ps.executeUpdate();
        }

        try (Connection conn = newConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT user_id, character_id, message_ids, chunk_text, "
                             + "       embedding::text AS emb_text, token_count, "
                             + "       array_length(message_ids, 1) AS msg_count "
                             + "FROM chat_embeddings WHERE user_id = 42 AND character_id = 7")) {
            assertThat(rs.next()).as("应能查到插入的行").isTrue();
            assertThat(rs.getLong("user_id")).isEqualTo(42L);
            assertThat(rs.getLong("character_id")).isEqualTo(7L);
            assertThat(rs.getInt("msg_count")).as("message_ids 数组长度 = 3").isEqualTo(3);
            assertThat(rs.getString("chunk_text")).isEqualTo("测试切片：今天天气真好");
            assertThat(rs.getInt("token_count")).isEqualTo(12);

            // embedding::text 形如 "[0,0.000977,0.001953,...]"，按逗号切片应正好 1024 个分量
            String embText = rs.getString("emb_text");
            assertThat(embText).startsWith("[").endsWith("]");
            String inner = embText.substring(1, embText.length() - 1);
            String[] parts = inner.split(",");
            assertThat(parts.length).as("embedding 向量维度 = 1024").isEqualTo(1024);
        }
    }
}
