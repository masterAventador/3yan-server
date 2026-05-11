package com.sanyan.repository;

import com.sanyan.entity.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class RepositoryTest {

    @Autowired private UserRepository userRepository;
    @Autowired private AiCharacterRepository characterRepository;
    @Autowired private MessageRepository messageRepository;

    @Test
    void shouldSaveAndFindUser() {
        User user = new User();
        user.setPhone("13800138000");
        user.setPassword("hashed");
        user.setNickname("测试用户");
        User saved = userRepository.save(user);

        assertThat(saved.getId()).isNotNull();
        assertThat(userRepository.findByPhone("13800138000")).isPresent();
    }

    @Test
    void shouldSaveCharacter() {
        AiCharacter c = new AiCharacter();
        c.setName("小满");
        c.setSystemPrompt("你是小满");
        c.setGreeting("你好");
        AiCharacter saved = characterRepository.save(c);

        assertThat(saved.getId()).isNotNull();
    }

    @Test
    void shouldSaveAndQueryMessage() {
        Message msg = new Message();
        msg.setUserId(1L);
        msg.setSenderType("user");
        msg.setContent("你好");
        Message saved = messageRepository.save(msg);

        assertThat(saved.getId()).isNotNull();
        assertThat(messageRepository.findByUserIdOrderByIdDesc(1L, PageRequest.of(0, 10)))
                .hasSize(1);
    }
}
