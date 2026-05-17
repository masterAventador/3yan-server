package com.sanyan.chat;

import com.sanyan.chat.api.ChatApiImpl;
import com.sanyan.chat.internal.MessageRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;

import com.sanyan.character.CharacterApi;
import com.sanyan.llm.LlmApi;
import com.sanyan.memory.MemoryApi;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * sanyan-chat-core 上下文冒烟测试（S3 Phase 5）：
 * 验证拆模块后 Spring 仍然能装配出 ChatApi Bean（实现为 ChatApiImpl）+ MessageRepository。
 *
 * <p>chat-core 依赖 user-api / character-api / llm-api / memory-api 等契约，但只在测试上下文
 * 装配 chat-core 自身 + foundation。其他业务 -api 的 Bean 实现（CharacterApi / LlmApi /
 * MemoryApi）不属于本模块，但 AiService 通过 {@code @RequiredArgsConstructor} 注入了它们，
 * 所以本测试用 {@link MockBean} 替换掉，避免装配失败。
 *
 * <p>boot config 用模块本地 {@link ChatTestConfig}（@ComponentScan 显式覆盖 com.sanyan.chat
 * + com.sanyan.common），思路与 UserApplicationContextIT / LlmApplicationContextIT 同。
 *
 * <p>chat-core 内有 JPA Entity（MessageEntity）+ Repository，所以需要 @EntityScan +
 * @EnableJpaRepositories + @AutoConfigureTestDatabase（H2 内存库）。
 */
@SpringBootTest(classes = ChatApplicationContextIT.ChatTestConfig.class)
@AutoConfigureTestDatabase
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        // Redis lazy 连接，本测试不调任何 KvCache 方法，给占位即可
        "sanyan.jwt.secret=test-secret-for-application-context-it-only-32chars",
        "sanyan.jwt.expiration-days=7"
})
class ChatApplicationContextIT {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @ComponentScan(basePackages = {"com.sanyan.chat", "com.sanyan.common"})
    @EntityScan(basePackages = "com.sanyan.chat")
    @EnableJpaRepositories(basePackages = "com.sanyan.chat")
    static class ChatTestConfig {}

    // 其他业务 -api 的实现不在 chat-core 内，mock 掉避免装配失败
    @MockBean
    private CharacterApi characterApi;
    @MockBean
    private LlmApi llmApi;
    @MockBean
    private MemoryApi memoryApi;

    @Autowired
    private ChatApi chatApi;

    @Autowired
    private MessageRepository messageRepository;

    @Test
    void contextLoads_chatApiBeanInjected() {
        assertThat(chatApi).isNotNull();
        assertThat(chatApi).isInstanceOf(ChatApiImpl.class);
        assertThat(messageRepository).isNotNull();
    }
}
