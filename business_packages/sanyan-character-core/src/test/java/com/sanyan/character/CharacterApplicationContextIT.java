package com.sanyan.character;

import com.sanyan.character.api.CharacterApiImpl;
import com.sanyan.character.internal.AiCharacterRepository;
import com.sanyan.common.test.TestApplication;
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
 * sanyan-character-core 上下文冒烟测试（S3 Phase 2）：
 * 验证拆模块后 Spring 仍然能装配出 CharacterApi Bean（实现为 CharacterApiImpl）和 AiCharacterRepository。
 *
 * <p>TestApplication 位于 com.sanyan.common.test，默认 @ComponentScan 根扫不到 com.sanyan.character，
 * 因此这里显式 @ComponentScan / @EntityScan / @EnableJpaRepositories 把扫描范围放宽到 com.sanyan.character。
 * H2 内存库 + AutoConfigureTestDatabase 让 JPA starter 能起来。
 *
 * <p>关掉 Flyway —— character-core 本身不带 migration（migration 仍在 sanyan-business），
 * 仅靠 hibernate ddl-auto=create-drop 拉起 ai_character 表足够本测试用。
 */
@SpringBootTest(classes = TestApplication.class)
@ContextConfiguration(classes = TestApplication.class)
// 同时扫 com.sanyan.character（本模块业务 Bean）和 com.sanyan.common（foundation Bean）
@ComponentScan(basePackages = {"com.sanyan.character", "com.sanyan.common"})
@EntityScan(basePackages = "com.sanyan.character")
@EnableJpaRepositories(basePackages = "com.sanyan.character")
@AutoConfigureTestDatabase
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class CharacterApplicationContextIT {

    @Autowired
    private CharacterApi characterApi;

    @Autowired
    private AiCharacterRepository repository;

    @Test
    void contextLoads_characterApiBeanInjected() {
        assertThat(characterApi).isNotNull();
        assertThat(characterApi).isInstanceOf(CharacterApiImpl.class);
        assertThat(repository).isNotNull();
    }
}
