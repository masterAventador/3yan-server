package com.sanyan;

import com.sanyan.common.test.PostgresTestcontainerSupport;
import com.sanyan.user.internal.UserAuthIdentityEntity;
import com.sanyan.user.internal.UserAuthIdentityRepository;
import com.sanyan.user.internal.oauth.Provider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * user_auth_identity 表 + UserAuthIdentityRepository 集成测试（第三方登录 Task 1）。
 *
 * <p>放在 bootstrap 而非 sanyan-user-core：V12 migration 文件位于 bootstrap 的
 * {@code db/migration} 资源目录，只有 bootstrap 的 test classpath 才能让 Flyway 把 V12 跑出来；
 * bootstrap 同时 compile 依赖 sanyan-user-core，故 entity/repo 在此可见。与 {@link Plan2EndToEndIT}
 * 一样继承 {@link PostgresTestcontainerSupport}（真实 PG17）走 Flyway V1→V12 建表，验证：
 *
 * <ol>
 *   <li>save 一条身份后 {@code findByProviderAndExternalId} 命中</li>
 *   <li>同 (provider, external_id) 再 save → flush 触发 uk_provider_external 唯一约束违反</li>
 *   <li>同 user_id 同 provider 第二条 → flush 触发 uk_provider_user 唯一约束违反</li>
 * </ol>
 *
 * <p>user_auth_identity.user_id 有 FK → users(id)，每个用例先用 native query seed 一个 user。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@AutoConfigureTestDatabase(replace = NONE)
class UserAuthIdentityRepositoryIT extends PostgresTestcontainerSupport {

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestcontainerSupport::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestcontainerSupport::username);
        registry.add("spring.datasource.password", PostgresTestcontainerSupport::password);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        // Flyway V1→V12 跑出完整 schema（含 V12 user_auth_identity）
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("spring.flyway.clean-disabled", () -> "false");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.autoconfigure.exclude", () ->
                "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration");
        registry.add("sanyan.jwt.secret", () ->
                "test-secret-key-at-least-256-bits-long-for-hmac-sha-testing");
        registry.add("sanyan.jwt.expiration-days", () -> "1");
        registry.add("sanyan.doubao.api-key", () -> "test");
        registry.add("sanyan.doubao.model", () -> "test");
        registry.add("sanyan.doubao.base-url", () -> "http://localhost:9999");
        registry.add("sanyan.cos.secret-id", () -> "test");
        registry.add("sanyan.cos.secret-key", () -> "test");
        registry.add("sanyan.cos.bucket", () -> "test-bucket");
        registry.add("sanyan.cos.region", () -> "ap-guangzhou");
        registry.add("sanyan.tts.enabled", () -> "false");
        registry.add("sanyan.tts.app-id", () -> "test");
        registry.add("sanyan.tts.access-token", () -> "test");
    }

    private static final Long USER_ID = 5001L;
    private static final Long OTHER_USER_ID = 5002L;

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private UserAuthIdentityRepository repository;

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager em;

    /** user_auth_identity.user_id 有 FK → users(id)，先 seed 两个 user。 */
    @BeforeEach
    @Transactional
    void seedUsers() {
        em.createNativeQuery(
                "INSERT INTO users (id, phone, password, nickname) VALUES " +
                "(5001, 'fk-5001', 'x', 'fk-5001'), " +
                "(5002, 'fk-5002', 'x', 'fk-5002') " +
                "ON CONFLICT (id) DO NOTHING"
        ).executeUpdate();
    }

    @Test
    @Transactional
    void save_thenFindByProviderAndExternalId_hits() {
        UserAuthIdentityEntity identity = new UserAuthIdentityEntity();
        identity.setUserId(USER_ID);
        identity.setProvider(Provider.APPLE);
        identity.setExternalId("sub123");
        repository.saveAndFlush(identity);

        Optional<UserAuthIdentityEntity> found =
                repository.findByProviderAndExternalId(Provider.APPLE, "sub123");

        assertThat(found).isPresent();
        assertThat(found.get().getUserId()).isEqualTo(USER_ID);
        assertThat(found.get().getProvider()).isEqualTo(Provider.APPLE);
        assertThat(found.get().getId()).isNotNull();
        assertThat(found.get().getCreatedAt()).isNotNull();
    }

    @Test
    @Transactional
    void duplicateProviderExternalId_violatesUkProviderExternal() {
        UserAuthIdentityEntity first = new UserAuthIdentityEntity();
        first.setUserId(USER_ID);
        first.setProvider(Provider.APPLE);
        first.setExternalId("dup-sub");
        repository.saveAndFlush(first);

        UserAuthIdentityEntity dup = new UserAuthIdentityEntity();
        dup.setUserId(OTHER_USER_ID); // 不同 user，但同 (provider, external_id)
        dup.setProvider(Provider.APPLE);
        dup.setExternalId("dup-sub");

        assertThatThrownBy(() -> repository.saveAndFlush(dup))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void sameUserSameProviderTwice_violatesUkProviderUser() {
        UserAuthIdentityEntity first = new UserAuthIdentityEntity();
        first.setUserId(USER_ID);
        first.setProvider(Provider.WECHAT);
        first.setExternalId("openid-a");
        repository.saveAndFlush(first);

        UserAuthIdentityEntity second = new UserAuthIdentityEntity();
        second.setUserId(USER_ID); // 同 user 同 provider，不同 external_id
        second.setProvider(Provider.WECHAT);
        second.setExternalId("openid-b");

        assertThatThrownBy(() -> repository.saveAndFlush(second))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
