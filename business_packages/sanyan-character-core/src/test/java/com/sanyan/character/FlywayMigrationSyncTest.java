package com.sanyan.character;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan 3 A1 引入的 Flyway 双拷贝同步守护测试（character-core 版）。
 *
 * <p>character-core 也有 Testcontainers IT（如 {@code AiCharacterPersonaSchemaIT}）依赖
 * {@code PostgresTestcontainerSupport.runMigrationsUpTo(...)}，后者从 classpath
 * 加载 {@code db/migration}。所以 character-core 也维护一份 SQL 副本在
 * {@code src/test/resources/db/migration/}。
 *
 * <p>本测试与 memory-core 的同名测试对等，防止 bootstrap 主拷贝与本模块副本漂移。
 *
 * <p>如果哪天 character-core IT 不再需要 db/migration，删本测试 +
 * 删 character-core/src/test/resources/db/migration/ 即可。
 */
class FlywayMigrationSyncTest {

    private static final Path BOOTSTRAP_DIR = Path.of(
            "../../bootstrap/src/main/resources/db/migration");
    private static final Path CHARACTER_TEST_DIR = Path.of(
            "src/test/resources/db/migration");

    @Test
    void flywayMigrationsMustBeInSyncBetweenBootstrapAndCharacterCoreTest() throws IOException {
        if (!Files.isDirectory(BOOTSTRAP_DIR)) {
            return;
        }
        List<Path> bootstrapSqls;
        List<Path> characterTestSqls;
        try (Stream<Path> bs = Files.list(BOOTSTRAP_DIR);
             Stream<Path> ct = Files.list(CHARACTER_TEST_DIR)) {
            bootstrapSqls = bs.filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .sorted().toList();
            characterTestSqls = ct.filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .sorted().toList();
        }

        List<String> bootstrapNames = bootstrapSqls.stream()
                .map(p -> p.getFileName().toString()).sorted().toList();
        List<String> characterNames = characterTestSqls.stream()
                .map(p -> p.getFileName().toString()).sorted().toList();
        assertThat(characterNames)
                .as("character-core/src/test/resources/db/migration 必须与 bootstrap 主拷贝同名同数")
                .containsExactlyElementsOf(bootstrapNames);

        for (int i = 0; i < bootstrapSqls.size(); i++) {
            Path bs = bootstrapSqls.get(i);
            Path ct = characterTestSqls.get(i);
            byte[] bsBytes = Files.readAllBytes(bs);
            byte[] ctBytes = Files.readAllBytes(ct);
            assertThat(ctBytes)
                    .as("文件 %s 在 bootstrap 与 character-core test 拷贝不一致——需同步",
                            bs.getFileName())
                    .containsExactly(bsBytes);
        }
    }
}
