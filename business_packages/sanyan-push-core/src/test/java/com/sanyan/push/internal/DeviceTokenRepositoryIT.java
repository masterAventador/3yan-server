package com.sanyan.push.internal;

import com.sanyan.push.internal.fixtures.DeviceTokenTestFixtures;
import com.sanyan.common.test.PostgresTestcontainerSupport;
import com.sanyan.common.test.TestApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * G1：DeviceTokenEntity + Repository roundtrip + 自定义查询验证。
 * Testcontainers PG，schema 由 Flyway V1-V11 生成，ddl-auto=none。device_tokens 无外键。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@ContextConfiguration(classes = TestApplication.class)
@EntityScan(basePackages = "com.sanyan.push")
@EnableJpaRepositories(basePackages = "com.sanyan.push")
class DeviceTokenRepositoryIT extends PostgresTestcontainerSupport {

    @DynamicPropertySource
    static void pgProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestcontainerSupport::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestcontainerSupport::username);
        registry.add("spring.datasource.password", PostgresTestcontainerSupport::password);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired
    DeviceTokenRepository repo;

    @Autowired
    TestEntityManager em;

    @Test
    void save_and_reload_should_roundtrip_with_defaults() {
        DeviceTokenEntity token = DeviceTokenTestFixtures.iosApns(7L, "tok-abc");
        Long id = repo.save(token).getId();
        em.flush();
        em.clear();

        DeviceTokenEntity loaded = repo.findById(id).orElseThrow();
        assertThat(loaded.getUserId()).isEqualTo(7L);
        assertThat(loaded.getPlatform()).isEqualTo("ios");
        assertThat(loaded.getVendor()).isEqualTo("apns");
        assertThat(loaded.getToken()).isEqualTo("tok-abc");
        assertThat(loaded.getActive()).isTrue();
        assertThat(loaded.getRegisteredAt()).isNotNull();
        assertThat(loaded.getLastSeen()).isNotNull();
    }

    @Test
    void upsert_should_reactivate_and_refresh_last_seen() {
        DeviceTokenEntity inactive = DeviceTokenTestFixtures.iosApns(11L, "tok-upsert");
        inactive.setActive(false);
        Instant oldLastSeen = Instant.now().minusSeconds(3600);
        inactive.setLastSeen(oldLastSeen);
        Long id = repo.save(inactive).getId();
        em.flush();
        em.clear();

        DeviceTokenEntity existing = repo.findById(id).orElseThrow();
        existing.setActive(true);
        existing.setLastSeen(Instant.now());
        repo.save(existing);
        em.flush();
        em.clear();

        DeviceTokenEntity reloaded = repo.findById(id).orElseThrow();
        assertThat(reloaded.getActive()).isTrue();
        assertThat(reloaded.getLastSeen()).isAfterOrEqualTo(oldLastSeen);
    }

    @Test
    void findByUserIdAndActiveTrue_should_return_only_active() {
        repo.save(DeviceTokenTestFixtures.iosApns(8L, "active-1"));
        DeviceTokenEntity inactive = DeviceTokenTestFixtures.iosApns(8L, "inactive-1");
        inactive.setActive(false);
        repo.save(inactive);
        em.flush();
        em.clear();

        List<DeviceTokenEntity> active = repo.findByUserIdAndActiveTrue(8L);

        assertThat(active).hasSize(1);
        assertThat(active.get(0).getToken()).isEqualTo("active-1");
    }

    @Test
    void findByUserIdAndPlatformAndVendorAndToken_should_locate_existing_row() {
        repo.save(DeviceTokenTestFixtures.iosApns(9L, "tok-xyz"));
        em.flush();
        em.clear();

        Optional<DeviceTokenEntity> found =
                repo.findByUserIdAndPlatformAndVendorAndToken(9L, "ios", "apns", "tok-xyz");

        assertThat(found).isPresent();
        assertThat(found.get().getUserId()).isEqualTo(9L);
    }
}
