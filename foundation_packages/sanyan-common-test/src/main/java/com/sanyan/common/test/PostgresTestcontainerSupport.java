package com.sanyan.common.test;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

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
 *           Flyway.configure()
 *                 .dataSource(jdbcUrl(), username(), password())
 *                 .load()
 *                 .migrate();
 *           // assertions...
 *       }
 *   }
 * }</pre>
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
}
