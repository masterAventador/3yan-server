package com.sanyan.embedding.internal;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Task M2b：BgeM3DjlAdapter 真实加载 BGE-M3 ONNX 模型的集成测试。
 *
 * <p>{@code @Tag("slow")} → 默认被 surefire / failsafe 排除（见 pom.xml 的 {@code excludedGroups}）。
 * CI 显式 {@code mvn -DexcludedGroups= verify} 时才执行；本地不跑（首次下载模型几十秒 + 占内存 ~1 GiB）。
 *
 * <p>验证：
 * <ul>
 *   <li>{@code ApplicationReadyEvent} 触发后，adapter 在合理时间内（≤ 5 min）进入 ready 状态</li>
 *   <li>{@code embed("你好")} 返回单条向量，维度 1024</li>
 *   <li>向量浮点数都是 finite</li>
 * </ul>
 */
@Tag("slow")
@SpringBootTest(classes = com.sanyan.embedding.EmbeddingTestApplication.class)
@TestPropertySource(properties = {
        "sanyan.embedding.internal-token=slow-it-token",
        "sanyan.embedding.model.name=BAAI/bge-m3",
})
class BgeM3DjlAdapterIT {

    @Autowired
    private BgeM3DjlAdapter adapter;

    @Test
    void embed_shouldReturn1024DimVectorAfterModelReady() throws Exception {
        // 模型加载是 ApplicationReadyEvent 触发后异步开始，最多等 5 分钟
        await().atMost(Duration.ofMinutes(5))
               .pollInterval(Duration.ofSeconds(2))
               .until(adapter::isReady);

        List<float[]> vectors = adapter.embed(List.of("你好"));

        assertThat(vectors).hasSize(1);
        float[] vec = vectors.get(0);
        assertThat(vec).hasSize(1024);
        for (float f : vec) {
            assertThat(Float.isFinite(f)).as("向量分量必须是 finite").isTrue();
        }
    }
}
