package com.sanyan.memory.internal.profile;

import com.sanyan.common.test.PostgresTestcontainerSupport;
import com.sanyan.common.test.TestApplication;
import com.sanyan.memory.internal.profile.fixtures.MemoryProfileTestFixtures;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * Plan 2 Task O1：MemoryProfileRepository 集成测试。
 *
 * <p>验证 4 个核心场景：
 * <ol>
 *   <li>{@code saveAndFindByCompositePk_persistsAllFields}：复合 PK (userId, characterId) + 字段往返</li>
 *   <li>{@code findByUserIdAndCharacterId_returnsOptional}：派生查询方法存在 + 不存在返回 Optional.empty</li>
 *   <li>{@code jsonbRoundTrip_preservesNestedStructureAndNulls}：嵌套 map / 数组 / null 值往返保形</li>
 *   <li>{@code optimisticLock_versionIncrementsOnUpdate}：@Version 字段更新后自增</li>
 * </ol>
 *
 * <p>用 Testcontainers PG 而非 H2，原因：
 * <ul>
 *   <li>JSONB 类型 H2 不原生支持，必须用真 PG</li>
 *   <li>与 V6__init_memory_profiles.sql 字段类型对齐</li>
 *   <li>生产是 PG 17</li>
 * </ul>
 *
 * <p>{@code @DataJpaTest} 默认 H2 替换 + Hibernate ddl-auto=create-drop。本测试关闭 H2 替换并让
 * Hibernate 从 Entity 注解生成 schema（参考 N1 task 的 MemorySummaryRepositoryIT 模式）。
 *
 * <p>Schema 是否对齐 V6 migration schema（JSONB 列、复合 PK、version、updated_at NOT NULL）
 * 由 bootstrap 模块的 V6MigrationIT 单独保证；本测试只关心 Entity ↔ Repository ↔ JPA 链路。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@ContextConfiguration(classes = TestApplication.class)
@EntityScan(basePackages = "com.sanyan.memory")
@EnableJpaRepositories(basePackages = "com.sanyan.memory")
class MemoryProfileRepositoryIT extends PostgresTestcontainerSupport {

    @DynamicPropertySource
    static void pgProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestcontainerSupport::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestcontainerSupport::username);
        registry.add("spring.datasource.password", PostgresTestcontainerSupport::password);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @Autowired
    private MemoryProfileRepository repository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void saveAndFindByCompositePk_persistsAllFields() {
        MemoryProfileEntity saved = repository.save(MemoryProfileTestFixtures.validProfile());

        assertThat(saved.getUpdatedAt()).as("@PrePersist 应默认填充 updatedAt").isNotNull();
        assertThat(saved.getVersion()).as("@Version 初次保存应为 0").isEqualTo(0);

        // 用复合 PK 重新查
        MemoryProfileId pk = new MemoryProfileId(1L, 1L);
        Optional<MemoryProfileEntity> loaded = repository.findById(pk);
        assertThat(loaded).isPresent();
        MemoryProfileEntity e = loaded.get();
        assertThat(e.getUserId()).isEqualTo(1L);
        assertThat(e.getCharacterId()).isEqualTo(1L);
        assertThat(e.getProfileJsonb()).containsKeys("basic_info", "preferences", "events", "emotion_line");
    }

    @Test
    void findByUserIdAndCharacterId_returnsOptional() {
        repository.save(MemoryProfileTestFixtures.validProfile());

        Optional<MemoryProfileEntity> hit = repository.findByUserIdAndCharacterId(1L, 1L);
        assertThat(hit).as("已存在的 (user, character) 应返回非空 Optional").isPresent();
        assertThat(hit.get().getUserId()).isEqualTo(1L);

        Optional<MemoryProfileEntity> miss = repository.findByUserIdAndCharacterId(999L, 999L);
        assertThat(miss).as("不存在的 (user, character) 应返回空 Optional").isEmpty();
    }

    @Test
    void jsonbRoundTrip_preservesNestedStructureAndNulls() {
        // 构造一个含嵌套对象 + 数组 + null 的复杂 JSONB
        Map<String, Object> jsonb = new LinkedHashMap<>();

        Map<String, Object> basicInfo = new LinkedHashMap<>();
        basicInfo.put("name", "小婉");
        basicInfo.put("age", null);                  // null 字段
        basicInfo.put("occupation", "前端工程师");
        basicInfo.put("hometown", null);
        jsonb.put("basic_info", basicInfo);

        Map<String, Object> preferences = new LinkedHashMap<>();
        preferences.put("foods", List.of("寿司", "拉面"));
        preferences.put("movies", new ArrayList<>());
        preferences.put("colors", List.of("蓝色"));
        preferences.put("music", new ArrayList<>());
        jsonb.put("preferences", preferences);

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "interview");
        event.put("content", "周三技术面试");
        event.put("date", null);
        jsonb.put("events", List.of(event));

        jsonb.put("emotion_line", List.of("焦虑", "兴奋"));

        MemoryProfileEntity input = MemoryProfileTestFixtures.validProfile();
        input.setProfileJsonb(jsonb);
        repository.saveAndFlush(input);

        // 清缓存，强制从 DB 重新加载（确保走真实 JSONB 序列化往返）
        entityManager.clear();

        Optional<MemoryProfileEntity> loaded = repository.findByUserIdAndCharacterId(1L, 1L);
        assertThat(loaded).isPresent();
        Map<String, Object> roundTrip = loaded.get().getProfileJsonb();

        @SuppressWarnings("unchecked")
        Map<String, Object> bi = (Map<String, Object>) roundTrip.get("basic_info");
        assertThat(bi.get("name")).isEqualTo("小婉");
        assertThat(bi.get("age")).as("null 字段应保留为 null").isNull();
        assertThat(bi.get("occupation")).isEqualTo("前端工程师");
        assertThat(bi.get("hometown")).isNull();

        @SuppressWarnings("unchecked")
        Map<String, Object> prefs = (Map<String, Object>) roundTrip.get("preferences");
        @SuppressWarnings("unchecked")
        List<String> foods = (List<String>) prefs.get("foods");
        assertThat(foods).containsExactly("寿司", "拉面");
        assertThat((List<?>) prefs.get("movies")).isEmpty();
        @SuppressWarnings("unchecked")
        List<String> colors = (List<String>) prefs.get("colors");
        assertThat(colors).containsExactly("蓝色");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) roundTrip.get("events");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("type")).isEqualTo("interview");
        assertThat(events.get(0).get("content")).isEqualTo("周三技术面试");
        assertThat(events.get(0).get("date")).as("嵌套 null 应保留").isNull();

        @SuppressWarnings("unchecked")
        List<String> emotionLine = (List<String>) roundTrip.get("emotion_line");
        assertThat(emotionLine).containsExactly("焦虑", "兴奋");
    }

    @Test
    void optimisticLock_versionIncrementsOnUpdate() {
        MemoryProfileEntity initial = repository.saveAndFlush(MemoryProfileTestFixtures.validProfile());
        Integer v0 = initial.getVersion();
        assertThat(v0).as("初次保存 version=0（JPA @Version 起始值）").isEqualTo(0);

        // 第一次更新：改 JSONB 内容
        initial.setProfileJsonb(MemoryProfileTestFixtures.profileWithName("小婉").getProfileJsonb());
        repository.saveAndFlush(initial);
        entityManager.clear();

        MemoryProfileEntity afterFirstUpdate =
                repository.findByUserIdAndCharacterId(1L, 1L).orElseThrow();
        assertThat(afterFirstUpdate.getVersion())
                .as("第一次更新后 version 应自增到 1")
                .isEqualTo(1);

        // 第二次更新：再改 JSONB
        afterFirstUpdate.setProfileJsonb(
                MemoryProfileTestFixtures.profileWithFoods(List.of("寿司")).getProfileJsonb());
        repository.saveAndFlush(afterFirstUpdate);
        entityManager.clear();

        MemoryProfileEntity afterSecondUpdate =
                repository.findByUserIdAndCharacterId(1L, 1L).orElseThrow();
        assertThat(afterSecondUpdate.getVersion())
                .as("第二次更新后 version 应自增到 2")
                .isEqualTo(2);
    }
}
