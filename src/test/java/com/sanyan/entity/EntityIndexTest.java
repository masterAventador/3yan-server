package com.sanyan.entity;

import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class EntityIndexTest {

    @Test
    void message_hasCreatedAtIndex() {
        Table table = Message.class.getAnnotation(Table.class);
        assertThat(Arrays.stream(table.indexes()).map(Index::columnList))
                .as("Message 应在 createdAt 上有索引")
                .anyMatch(c -> c.contains("createdAt"));
    }

    @Test
    void conversation_hasUserAndLastMessageIndex() {
        Table table = Conversation.class.getAnnotation(Table.class);
        assertThat(Arrays.stream(table.indexes()).map(Index::columnList))
                .as("Conversation 应在 user_id + last_message_at 上有组合索引")
                .anyMatch(c -> (c.contains("user_id") || c.contains("userId"))
                        && (c.contains("last_message_at") || c.contains("lastMessageAt")));
    }
}
