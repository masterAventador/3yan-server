package com.sanyan.memory;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S3 Phase 6 引入的 Flyway 双拷贝同步守护测试。
 *
 * <p>历史背景：S3 Phase 6 删 sanyan-business 时，原 Flyway V*.sql 主版本迁到
 * bootstrap/src/main/resources/db/migration/，同时为了 memory-core IT 测试能跑
 * (不引 bootstrap jar)，把同一批 SQL 复制了一份到 memory-core/src/test/resources/db/migration/。
 *
 * <p>本测试目的：防止两份拷贝漂移。每次有人改 bootstrap 那边的 V*.sql，必须同步
 * memory-core 这边，否则本测试 fail 提醒。
 *
 * <p>如果哪天 memory-core IT 不再需要 db/migration（如改用 ddl-auto），删本测试 +
 * 删 memory-core/src/test/resources/db/migration/ 即可。
 */
class FlywayMigrationSyncTest {

    private static final Path BOOTSTRAP_DIR = Path.of(
            "../../bootstrap/src/main/resources/db/migration");
    private static final Path MEMORY_TEST_DIR = Path.of(
            "src/test/resources/db/migration");

    @Test
    void flywayMigrationsMustBeInSyncBetweenBootstrapAndMemoryCoreTest() throws IOException {
        if (!Files.isDirectory(BOOTSTRAP_DIR)) {
            // 主拷贝不存在 — 跳过（防止 IDE 跑测试时 cwd 不对触发误报）
            return;
        }
        List<Path> bootstrapSqls;
        List<Path> memoryTestSqls;
        try (Stream<Path> bs = Files.list(BOOTSTRAP_DIR);
             Stream<Path> mt = Files.list(MEMORY_TEST_DIR)) {
            bootstrapSqls = bs.filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .sorted().toList();
            memoryTestSqls = mt.filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .sorted().toList();
        }

        // 文件名列表必须一致
        List<String> bootstrapNames = bootstrapSqls.stream()
                .map(p -> p.getFileName().toString()).sorted().toList();
        List<String> memoryNames = memoryTestSqls.stream()
                .map(p -> p.getFileName().toString()).sorted().toList();
        assertThat(memoryNames)
                .as("memory-core/src/test/resources/db/migration 必须与 bootstrap 主拷贝同名同数")
                .containsExactlyElementsOf(bootstrapNames);

        // 每份内容必须 byte-identical
        for (int i = 0; i < bootstrapSqls.size(); i++) {
            Path bs = bootstrapSqls.get(i);
            Path mt = memoryTestSqls.get(i);
            byte[] bsBytes = Files.readAllBytes(bs);
            byte[] mtBytes = Files.readAllBytes(mt);
            assertThat(mtBytes)
                    .as("文件 %s 在 bootstrap 与 memory-core test 拷贝不一致——需同步",
                            bs.getFileName())
                    .containsExactly(bsBytes);
        }
    }
}
