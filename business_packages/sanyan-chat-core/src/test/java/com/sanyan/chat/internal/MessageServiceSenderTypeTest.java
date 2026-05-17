package com.sanyan.chat.internal;

import com.sanyan.chat.SenderType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MessageServiceSenderTypeTest {

    @Test
    void source_noHardcodedAiString() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/sanyan/chat/internal/MessageService.java"));
        assertThat(source)
                .as("应使用 SenderType.AI 常量而不是硬编码 \"ai\"")
                .doesNotContain("setSenderType(\"ai\")");
    }

    @Test
    void senderType_constantsUnchanged() {
        assertThat(SenderType.AI).isEqualTo("ai");
        assertThat(SenderType.USER).isEqualTo("user");
    }
}
