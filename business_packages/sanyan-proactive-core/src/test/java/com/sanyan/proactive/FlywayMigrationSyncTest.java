package com.sanyan.proactive;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Plan 4 B2 引入的 Flyway 副本同步守护测试（proactive-core 版）：防 bootstrap 主拷贝与本模块副本漂移。 */
class FlywayMigrationSyncTest {

    private static final Path BOOTSTRAP_DIR = Path.of("../../bootstrap/src/main/resources/db/migration");
    private static final Path PROACTIVE_TEST_DIR = Path.of("src/test/resources/db/migration");

    @Test
    void flywayMigrationsMustBeInSyncBetweenBootstrapAndProactiveCoreTest() throws IOException {
        if (!Files.isDirectory(BOOTSTRAP_DIR)) return;
        List<Path> bootstrapSqls; List<Path> proactiveTestSqls;
        try (Stream<Path> bs = Files.list(BOOTSTRAP_DIR); Stream<Path> pt = Files.list(PROACTIVE_TEST_DIR)) {
            bootstrapSqls = bs.filter(p -> p.getFileName().toString().endsWith(".sql")).sorted().toList();
            proactiveTestSqls = pt.filter(p -> p.getFileName().toString().endsWith(".sql")).sorted().toList();
        }
        List<String> bootstrapNames = bootstrapSqls.stream().map(p -> p.getFileName().toString()).sorted().toList();
        List<String> proactiveNames = proactiveTestSqls.stream().map(p -> p.getFileName().toString()).sorted().toList();
        assertThat(proactiveNames).as("proactive-core 副本必须与 bootstrap 主拷贝同名同数").containsExactlyElementsOf(bootstrapNames);
        for (int i = 0; i < bootstrapSqls.size(); i++) {
            assertThat(Files.readAllBytes(proactiveTestSqls.get(i)))
                    .as("文件 %s 在 bootstrap 与 proactive-core test 拷贝不一致——需同步", bootstrapSqls.get(i).getFileName())
                    .containsExactly(Files.readAllBytes(bootstrapSqls.get(i)));
        }
    }
}
