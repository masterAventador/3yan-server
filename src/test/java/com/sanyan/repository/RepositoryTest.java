package com.sanyan.repository;

import com.sanyan.entity.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class RepositoryTest {

    @Autowired private UserRepository userRepository;
    @Autowired private AiCharacterRepository characterRepository;
    @Autowired private ConversationRepository conversationRepository;
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
    void shouldSaveConversationWithUniqueConstraint() {
        User user = userRepository.save(createUser("13800138001"));
        AiCharacter character = characterRepository.save(createCharacter("小晚"));

        Conversation conv = new Conversation();
        conv.setUserId(user.getId());
        conv.setCharacterId(character.getId());
        conv.setUnreadCount(0);
        Conversation saved = conversationRepository.save(conv);

        assertThat(saved.getId()).isNotNull();
        assertThat(conversationRepository.findByUserIdAndCharacterId(user.getId(), character.getId()))
                .isPresent();
    }

    @Test
    void shouldSaveAndPageMessages() {
        Message msg = new Message();
        msg.setConversationId(1L);
        msg.setSenderType("user");
        msg.setContentType("text");
        msg.setContent("你好");
        msg.setSource("reply");
        Message saved = messageRepository.save(msg);

        assertThat(saved.getId()).isNotNull();
    }

    private User createUser(String phone) {
        User u = new User();
        u.setPhone(phone);
        u.setPassword("hashed");
        u.setNickname("test");
        return u;
    }

    private AiCharacter createCharacter(String name) {
        AiCharacter c = new AiCharacter();
        c.setName(name);
        c.setSystemPrompt("你是" + name);
        c.setGreeting("你好呀");
        c.setType("preset");
        return c;
    }
}
