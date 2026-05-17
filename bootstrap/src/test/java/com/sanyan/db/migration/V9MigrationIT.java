package com.sanyan.db.migration;

import com.sanyan.common.test.PostgresTestcontainerSupport;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Plan 2.6 patch：验证 V9__memory_summaries_unique_period_end.sql 迁移。
 *
 * <p>V9 给 memory_summaries 加 UNIQUE (user_id, character_id, period_end_message_id)
 * 约束，用 {@code NULLS NOT DISTINCT} 让 NULL 也参与去重（PG 15+ 语法，项目用 PG 17）。
 *
 * <p>测试覆盖：
 * <ul>
 *   <li>{@code uq_memory_summaries_period_end} 唯一约束存在</li>
 *   <li>插入两条相同 (user_id, character_id, period_end_message_id) 行 → 第二条抛
 *       约束违例（{@link java.sql.SQLException}，被 Spring 翻译成
 *       {@link DataIntegrityViolationException}）</li>
 *   <li>(user_id, character_id) 相同但 period_end_message_id 不同 → 允许</li>
 *   <li>(user_id, character_id) 不同 → 允许</li>
 * </ul>
 */
class V9MigrationIT extends PostgresTestcontainerSupport {

    @Test
    void migration_addsUniqueConstraintOnPeriodEnd() throws Exception {
        runMigrationsUpTo("9");

        // pg_indexes 视图同时列 PK 索引 + UNIQUE 约束的 backing index
        try (Connection conn = newConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT indexdef FROM pg_indexes "
                             + "WHERE schemaname = 'public' AND tablename = 'memory_summaries' "
                             + "AND indexname = 'uq_memory_summaries_period_end'")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("V9 唯一约束 backing index 必须存在").isTrue();
                String def = rs.getString(1).toLowerCase();
                assertThat(def).contains("user_id");
                assertThat(def).contains("character_id");
                assertThat(def).contains("period_end_message_id");
                assertThat(def).contains("unique");
                assertThat(def).contains("nulls not distinct");
            }
        }
    }

    @Test
    void duplicatePeriodEnd_throwsConstraintViolation() throws Exception {
        runMigrationsUpTo("9");

        try (Connection conn = newConnection();
             Statement st = conn.createStatement()) {
            // 第一条正常插入
            st.executeUpdate(
                    "INSERT INTO memory_summaries "
                            + "(user_id, character_id, period_start_message_id, period_end_message_id, "
                            + "summary_text, message_count) "
                            + "VALUES (1, 1, 1, 31, '首次摘要', 30)");

            // 第二条 (user_id, character_id, period_end_message_id) 完全相同 → 应抛违例
            assertThatThrownBy(() -> st.executeUpdate(
                    "INSERT INTO memory_summaries "
                            + "(user_id, character_id, period_start_message_id, period_end_message_id, "
                            + "summary_text, message_count) "
                            + "VALUES (1, 1, 1, 31, '重复摘要', 30)"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("uq_memory_summaries_period_end");
        }
    }

    @Test
    void differentPeriodEnd_allowedForSameUserChar() throws Exception {
        runMigrationsUpTo("9");

        try (Connection conn = newConnection();
             Statement st = conn.createStatement()) {
            // 同 (user, char) 两条不同 period_end_message_id → 都允许（正常滚动摘要场景）
            st.executeUpdate(
                    "INSERT INTO memory_summaries "
                            + "(user_id, character_id, period_start_message_id, period_end_message_id, "
                            + "summary_text, message_count) "
                            + "VALUES (2, 1, 1, 30, '第一段', 30)");
            st.executeUpdate(
                    "INSERT INTO memory_summaries "
                            + "(user_id, character_id, period_start_message_id, period_end_message_id, "
                            + "summary_text, message_count) "
                            + "VALUES (2, 1, 31, 60, '第二段', 30)");

            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM memory_summaries WHERE user_id = 2 AND character_id = 1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).as("同 user/char 不同 period_end 应都能落库").isEqualTo(2);
            }
        }
    }

    @Test
    void differentUserOrCharacter_allowedForSamePeriodEnd() throws Exception {
        runMigrationsUpTo("9");

        try (Connection conn = newConnection();
             Statement st = conn.createStatement()) {
            // 同 period_end_message_id 但不同 user → 允许
            st.executeUpdate(
                    "INSERT INTO memory_summaries "
                            + "(user_id, character_id, period_start_message_id, period_end_message_id, "
                            + "summary_text, message_count) "
                            + "VALUES (3, 1, 1, 31, 'userA 摘要', 30)");
            st.executeUpdate(
                    "INSERT INTO memory_summaries "
                            + "(user_id, character_id, period_start_message_id, period_end_message_id, "
                            + "summary_text, message_count) "
                            + "VALUES (4, 1, 1, 31, 'userB 摘要', 30)");
            // 同 user 不同 character → 允许
            st.executeUpdate(
                    "INSERT INTO memory_summaries "
                            + "(user_id, character_id, period_start_message_id, period_end_message_id, "
                            + "summary_text, message_count) "
                            + "VALUES (3, 2, 1, 31, 'userA char2 摘要', 30)");

            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM memory_summaries WHERE period_end_message_id = 31")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).as("不同 user/char 同 period_end 都应能落库").isEqualTo(3);
            }
        }
    }
}
