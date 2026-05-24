package com.sanyan.llm;

import com.sanyan.llm.api.LlmApiImpl;
import com.sanyan.llm.internal.DeepSeekAdapter;
import com.sanyan.llm.internal.DoubaoAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * sanyan-llm-core 上下文冒烟测试（S3 Phase 3）：
 * 验证拆模块后 Spring 仍然能装配出 LlmApi Bean（实现为 LlmApiImpl）+ 两个 Adapter + Router。
 *
 * <p>boot config 用模块本地的 {@link LlmTestApplication}（@ComponentScan 显式覆盖 com.sanyan.llm
 * + com.sanyan.common），而不是 sanyan-common-test 里的 TestApplication——后者默认根扫不到
 * com.sanyan.llm，即使在测试类上加 @ComponentScan 也因 @SpringBootTest(classes=...) 锁定 boot
 * config 而失效。
 *
 * <p>llm 域本身无 JPA / Repository，sanyan-llm-core 也没引 data-jpa / h2，所以不需要
 * @AutoConfigureTestDatabase。
 *
 * <p>豆包 / DeepSeek 的 API key / base-url 显式塞 test 值（adapter @Value 都有默认值，但显式
 * 给个测试 key 可避免 base-url 拼到真实 ARK / DeepSeek 域名，更直观）。RestClient 在装配阶段
 * 不会真打外网，只是构造对象——本测试不发起任何远程调用，只校验 Bean 拓扑。
 */
@SpringBootTest(classes = LlmApplicationContextIT.LlmTestApplication.class)
@TestPropertySource(properties = {
        "sanyan.doubao.api-key=test-doubao",
        "sanyan.doubao.base-url=http://localhost:9999",
        "sanyan.doubao.model=test-doubao-model",
        "sanyan.llm.deepseek.api-key=test-deepseek",
        "sanyan.llm.deepseek.base-url=http://localhost:9998",
        "sanyan.llm.deepseek.model=test-deepseek-model"
})
class LlmApplicationContextIT {

    @Autowired
    private LlmApi llmApi;

    @Autowired
    private DoubaoAdapter doubaoAdapter;

    @Autowired
    private DeepSeekAdapter deepSeekAdapter;

    @Test
    void contextLoads_llmApiBeanInjected() {
        assertThat(llmApi).isNotNull();
        assertThat(llmApi).isInstanceOf(LlmApiImpl.class);
    }

    @Test
    void defaults_shouldRouteAllTasksToDeepSeek() {
        // 默认配置（doubao.task-types 空 / deepseek.task-types USER_FACING,BACKGROUND）
        // 必须满足：doubao 全 false，deepseek 全 true
        assertThat(doubaoAdapter.supports(LlmTaskType.USER_FACING)).isFalse();
        assertThat(doubaoAdapter.supports(LlmTaskType.BACKGROUND)).isFalse();
        assertThat(deepSeekAdapter.supports(LlmTaskType.USER_FACING)).isTrue();
        assertThat(deepSeekAdapter.supports(LlmTaskType.BACKGROUND)).isTrue();
    }

    /**
     * 模块本地 boot config：@ComponentScan 显式扫 com.sanyan.llm（本模块业务 Bean） +
     * com.sanyan.common（foundation 比如 HttpClientFactory 等）。
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @ComponentScan(basePackages = {"com.sanyan.llm", "com.sanyan.common"})
    static class LlmTestApplication {}
}
