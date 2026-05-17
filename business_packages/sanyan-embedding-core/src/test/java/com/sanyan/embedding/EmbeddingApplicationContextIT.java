package com.sanyan.embedding;

import com.sanyan.embedding.api.EmbeddingApiImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * sanyan-embedding-core 上下文冒烟测试（S3 Phase 4）：
 * 验证拆模块后 Spring 仍然能装配出 EmbeddingApi Bean（实现为 EmbeddingApiImpl，委托 SiliconFlowEmbeddingProvider）。
 *
 * <p>boot config 用模块本地的 {@link EmbeddingTestConfig}（@ComponentScan 显式覆盖
 * com.sanyan.embedding + com.sanyan.common），思路与 LlmApplicationContextIT 同——
 * 默认根扫不到 com.sanyan.embedding，即使在测试类上加 @ComponentScan 也因
 * @SpringBootTest(classes=...) 锁定 boot config 而失效。
 *
 * <p>embedding 域无 JPA / Repository，sanyan-embedding-core 没引 data-jpa / h2，
 * 所以不需要 @AutoConfigureTestDatabase。
 *
 * <p>SiliconFlow API key / base-url / model 显式塞 test 值。RestClient 在装配阶段
 * 不会真打外网，只是构造对象——本测试不发起任何远程调用，只校验 Bean 拓扑。
 */
@SpringBootTest(classes = EmbeddingApplicationContextIT.EmbeddingTestConfig.class)
@TestPropertySource(properties = {
        "sanyan.embedding.siliconflow.api-key=test",
        "sanyan.embedding.siliconflow.base-url=http://localhost:9999",
        "sanyan.embedding.siliconflow.model=test-model"
})
class EmbeddingApplicationContextIT {

    @Autowired
    private EmbeddingApi embeddingApi;

    @Test
    void contextLoads_embeddingApiBeanInjected() {
        assertThat(embeddingApi).isNotNull();
        assertThat(embeddingApi).isInstanceOf(EmbeddingApiImpl.class);
    }

    /**
     * 模块本地 boot config：@ComponentScan 显式扫 com.sanyan.embedding（本模块业务 Bean） +
     * com.sanyan.common（foundation 比如 HttpClientFactory 等）。
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @ComponentScan(basePackages = {"com.sanyan.embedding", "com.sanyan.common"})
    static class EmbeddingTestConfig {}
}
