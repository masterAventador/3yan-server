package com.sanyan.embedding;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task M2b：embedding-bootstrap 启动冒烟测试。
 *
 * <p>验证 Spring 上下文能正常装配（包扫描覆盖 sanyan-embedding-core 的所有 Bean +
 * common-error 的 ErrCodeConflictDetector 自动配置生效），但不真正加载 BGE-M3 模型——
 * 模型加载是 {@link org.springframework.boot.context.event.ApplicationReadyEvent}
 * 触发后异步执行；测试上下文虽然会发该事件，但 {@code BgeM3DjlAdapter#loadModel} 是
 * {@code @Async} 异步执行，测试结束时模型还在加载，不会拖慢测试。
 *
 * <p>注意：本测试需要联网下载 DJL 引擎 native 库（首次几十 MiB），不需要下载完整
 * BGE-M3 模型。CI 应该已经缓存。
 */
@SpringBootTest(properties = {
        "sanyan.embedding.internal-token=context-test-token",
        "server.port=0"  // 让测试不占用 8080
})
class EmbeddingApplicationContextIT {

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoads_andAdapterBeanIsRegistered() {
        assertThat(context.containsBean("bgeM3DjlAdapter")).isTrue();
        assertThat(context.containsBean("embeddingApiImpl")).isTrue();
        assertThat(context.containsBean("embeddingController")).isTrue();
        assertThat(context.containsBean("internalTokenInterceptor")).isTrue();
        assertThat(context.containsBean("embeddingHealthIndicator")).isTrue();
    }
}
