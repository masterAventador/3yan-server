package com.sanyan.user;

import com.sanyan.common.test.TestApplication;
import com.sanyan.user.api.UserApiImpl;
import com.sanyan.user.internal.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * sanyan-user-core 上下文冒烟测试（S3 Phase 1）：
 * 验证拆模块后 Spring 仍然能装配出 UserApi Bean（实现为 UserApiImpl）和 UserRepository。
 *
 * <p>TestApplication 位于 com.sanyan.common.test，默认 @ComponentScan 根扫不到 com.sanyan.user，
 * 因此这里显式 @ComponentScan / @EntityScan / @EnableJpaRepositories 把扫描范围放宽到 com.sanyan.user。
 * H2 内存库 + AutoConfigureTestDatabase 让 JPA starter 能起来。
 *
 * <p>关掉 Flyway —— user-core 本身不带 migration（migration 仍在 sanyan-business），
 * 仅靠 hibernate ddl-auto=create-drop 拉起 user 表足够本测试用。
 */
@SpringBootTest(classes = TestApplication.class)
@ContextConfiguration(classes = TestApplication.class)
// 同时扫 com.sanyan.user（本模块业务 Bean）和 com.sanyan.common（KvCache 等 foundation Bean）
@ComponentScan(basePackages = {"com.sanyan.user", "com.sanyan.common"})
@EntityScan(basePackages = "com.sanyan.user")
@EnableJpaRepositories(basePackages = "com.sanyan.user")
@AutoConfigureTestDatabase
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        // Redis 启动期是 lazy 连接的，不准备 Redis 也能完成 Bean 装配（本测试不调任何 KvCache 方法）。
        // JwtUtil 通过 @Value("${sanyan.jwt.secret}") 注入；这里给个测试占位密钥即可。
        "sanyan.jwt.secret=test-secret-for-application-context-it-only-32chars",
        "sanyan.jwt.expiration-days=7",
        // BindTicketUtil（common-auth）通过 @Value 直接读 sanyan.oauth.bind-ticket.secret，
        // 不在扫描包内给个 ≥32 字节占位密钥，否则 HMAC key 太短/缺失导致 bean 创建失败。
        "sanyan.oauth.bind-ticket.secret=test-bind-ticket-secret-for-application-context-it-only"
})
class UserApplicationContextIT {

    @Autowired
    private UserApi userApi;

    @Autowired
    private UserRepository userRepository;

    @Test
    void contextLoads_userApiBeanInjected() {
        assertThat(userApi).isNotNull();
        assertThat(userApi).isInstanceOf(UserApiImpl.class);
        assertThat(userRepository).isNotNull();
    }
}
