package com.sanyan.service;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class MessageServiceTransactionalTest {

    @Test
    void saveUserMessage_isAnnotatedWithTransactional() throws NoSuchMethodException {
        Method m = MessageService.class.getMethod("saveUserMessage", Long.class, String.class);
        Transactional tx = m.getAnnotation(Transactional.class);
        assertThat(tx).as("saveUserMessage 必须带 @Transactional").isNotNull();
    }

    @Test
    void handleAiReply_isAnnotatedWithTransactional() throws NoSuchMethodException {
        Method m = MessageService.class.getMethod("handleAiReply", Long.class);
        Transactional tx = m.getAnnotation(Transactional.class);
        assertThat(tx).as("handleAiReply 必须带 @Transactional").isNotNull();
    }
}
