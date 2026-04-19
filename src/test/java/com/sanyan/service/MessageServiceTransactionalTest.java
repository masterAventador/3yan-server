package com.sanyan.service;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class MessageServiceTransactionalTest {

    @Test
    void handleUserMessage_isAnnotatedWithTransactional() throws NoSuchMethodException {
        Method m = MessageService.class.getMethod(
                "handleUserMessage", Long.class, Long.class, String.class, String.class, String.class, Integer.class);
        Transactional tx = m.getAnnotation(Transactional.class);
        assertThat(tx).as("handleUserMessage 必须带 @Transactional 保护多次 save 的原子性").isNotNull();
    }
}
