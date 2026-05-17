package com.sanyan.common.test;

import org.flywaydb.core.Flyway;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 共享 Postgres Testcontainer。
 *
 * <p>所有需要在真实 Postgres（含 pgvector 扩展能力）上跑 Flyway / JPA 集成测试的
 * IT 都继承本类。容器在 JVM 级别只启一次（lazy holder），多个测试类共享同一个
 * Docker 容器，避免每个 IT 启停浪费时间。
 *
 * <p>选择 {@code pgvector/pgvector:pg17} 而非 {@code postgres:17}：与生产 PG 17
 * 主版本对齐，并且预装 pgvector 扩展，方便后续 chat_embeddings 表的迁移测试
 * （L3）复用同一基类。对于不需要 pgvector 的 V5 / V6 测试，pgvector 镜像也只是
 * vanilla pg17 + 一个未启用的扩展，行为完全等价于普通 PG。
 *
 * <p>使用 lazy holder 而非直接 static 启动：surefire 在跑 mvn test 时会扫描整个
 * test-classpath，加载所有测试相关的类（含本基类），若用 static 立即启动会导致
 * 不需要 PG 的纯单元测试也付出 ~1 秒的容器启动开销。lazy holder 只在第一次调用
 * {@link #postgres()} 时才启动容器。
 *
 * <p>子类典型用法：
 * <pre>{@code
 *   class V5MigrationIT extends PostgresTestcontainerSupport {
 *       @Test
 *       void schemaIsCreated() throws Exception {
 *           runMigrationsUpTo("5");
 *           Map<String, ColumnSpec> cols = describeColumns("memory_summaries");
 *           // assertions...
 *       }
 *   }
 * }</pre>
 *
 * <p>本基类还提供两个常用 helper：
 * <ul>
 *   <li>{@link #runMigrationsUpTo(String)}：跑 Flyway 到指定版本，先 clean 再 migrate
 *       保证用例隔离</li>
 *   <li>{@link #describeColumns(String)}：查 information_schema 拿表字段类型 / nullability，
 *       并自动识别 BIGSERIAL 伪类型（底层 udt_name 是 int8，但有 serial sequence 时归类
 *       为 bigserial）</li>
 * </ul>
 */
public abstract class PostgresTestcontainerSupport {

    private static final DockerImageName IMAGE =
            DockerImageName.parse("pgvector/pgvector:pg17")
                    .asCompatibleSubstituteFor("postgres");

    /**
     * Lazy holder：JVM classloader 保证 INSTANCE 只在第一次访问 {@link Holder} 时初始化，
     * 不需要 PG 的测试不会触发容器启动。
     */
    private static final class Holder {
        static final PostgreSQLContainer<?> INSTANCE;
        static {
            INSTANCE = new PostgreSQLContainer<>(IMAGE)
                    .withDatabaseName("sanyan_test")
                    .withUsername("sanyan_test")
                    .withPassword("sanyan_test")
                    .withReuse(false);
            INSTANCE.start();
        }
    }

    protected static PostgreSQLContainer<?> postgres() {
        return Holder.INSTANCE;
    }

    protected static String jdbcUrl() {
        return postgres().getJdbcUrl();
    }

    protected static String username() {
        return postgres().getUsername();
    }

    protected static String password() {
        return postgres().getPassword();
    }

    // ============ shared helpers ============

    /**
     * 取一个新的 JDBC 连接（调用方负责 close）。
     */
    protected static Connection newConnection() throws Exception {
        return DriverManager.getConnection(jdbcUrl(), username(), password());
    }

    /**
     * 跑 Flyway migration 到指定版本，先 {@code clean()} 再 {@code migrate()}，
     * 保证用例之间互相隔离，不依赖执行顺序。
     *
     * @param targetVersion Flyway target 版本号字符串，例如 {@code "5"} / {@code "6"}
     */
    protected static void runMigrationsUpTo(String targetVersion) {
        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl(), username(), password())
                .locations("classpath:db/migration")
                .target(targetVersion)
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
    }

    /**
     * 查指定表的字段类型 / nullability，返回 {@code 字段名 → ColumnSpec}。
     *
     * <p>对 {@code BIGSERIAL} 伪类型做特殊处理：底层 {@code udt_name} 是 {@code int8}，
     * 若该列有 serial sequence（{@code pg_get_serial_sequence} 非 null）则归类为
     * {@code bigserial}，否则归类为 {@code bigint}。
     */
    protected static Map<String, ColumnSpec> describeColumns(String tableName) throws Exception {
        Map<String, ColumnSpec> result = new LinkedHashMap<>();
        try (Connection conn = newConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT column_name, udt_name, is_nullable "
                             + "FROM information_schema.columns "
                             + "WHERE table_schema = 'public' AND table_name = ?")) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("column_name");
                    String udt = rs.getString("udt_name");
                    boolean nullable = "YES".equalsIgnoreCase(rs.getString("is_nullable"));
                    String displayType = udt;
                    if ("int8".equalsIgnoreCase(udt)) {
                        try (PreparedStatement seqCheck = conn.prepareStatement(
                                "SELECT pg_get_serial_sequence(?, ?)")) {
                            seqCheck.setString(1, tableName);
                            seqCheck.setString(2, name);
                            try (ResultSet sr = seqCheck.executeQuery()) {
                                if (sr.next() && sr.getString(1) != null) {
                                    displayType = "bigserial";
                                } else {
                                    displayType = "bigint";
                                }
                            }
                        }
                    }
                    result.put(name, new ColumnSpec(displayType, nullable));
                }
            }
        }
        return result;
    }

    /**
     * 字段元信息：类型名（PG 视角，参考 {@link #describeColumns}）+ 是否可空。
     */
    public record ColumnSpec(String typeName, boolean nullable) {}
}
