package com.sanyan.service;

import com.sanyan.entity.AiCharacter;
import com.sanyan.entity.Conversation;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class ProactiveServiceTransactionalTest {

    @Test
    void sendProactiveMessage_isAnnotatedWithTransactional() throws NoSuchMethodException {
        Method m = ProactiveService.class.getMethod(
                "sendProactiveMessage", Conversation.class, AiCharacter.class, String.class);
        Transactional tx = m.getAnnotation(Transactional.class);
        assertThat(tx)
                .as("sendProactiveMessage 多次 save（message + conversation）需要 @Transactional 保护原子性")
                .isNotNull();
    }
}
