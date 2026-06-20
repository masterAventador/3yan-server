package com.sanyan.chat.internal;

import com.sanyan.chat.SenderType;
import com.sanyan.common.test.PostgresTestcontainerSupport;
import com.sanyan.common.test.TestApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * P-T5：MessageRepository 取最近主动消息查询。
 * Testcontainers PG（schema 由 Flyway V1-V13 生成，is_proactive 列在 V13）。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@ContextConfiguration(classes = TestApplication.class)
@EntityScan(basePackages = "com.sanyan.chat")
@EnableJpaRepositories(basePackages = "com.sanyan.chat")
class MessageRepositoryIT extends PostgresTestcontainerSupport {

    @DynamicPropertySource
    static void pgProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestcontainerSupport::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestcontainerSupport::username);
        registry.add("spring.datasource.password", PostgresTestcontainerSupport::password);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired MessageRepository repository;
    @Autowired TestEntityManager em;

    /** message.user_id 外键引用 users(id)，先种一个用户满足约束。 */
    private void seedUser(Long userId) {
        em.getEntityManager()
                .createNativeQuery("INSERT INTO users(id, phone, password) VALUES (?1, ?2, 'x')")
                .setParameter(1, userId)
                .setParameter(2, "1380000" + userId)
                .executeUpdate();
    }

    private void persistMessage(Long userId, String senderType, String content, boolean proactive) {
        MessageEntity m = new MessageEntity();
        m.setUserId(userId);
        m.setSenderType(senderType);
        m.setContent(content);
        m.setProactive(proactive);
        em.persist(m);
    }

    @Test
    void findRecentProactive_returns_only_proactive_ai_messages_desc() {
        seedUser(1L);
        persistMessage(1L, SenderType.USER, "在吗", false);
        persistMessage(1L, SenderType.AI, "在的", false);
        persistMessage(1L, SenderType.AI, "早安", true);
        persistMessage(1L, SenderType.AI, "睡了吗", true);
        em.flush();

        List<MessageEntity> recent = repository
                .findByUserIdAndIsProactiveTrueOrderByIdDesc(1L, PageRequest.of(0, 10));

        assertThat(recent).hasSize(2);
        assertThat(recent.get(0).getContent()).isEqualTo("睡了吗"); // id 降序，最新在前
        assertThat(recent).allMatch(MessageEntity::isProactive);
    }
}
