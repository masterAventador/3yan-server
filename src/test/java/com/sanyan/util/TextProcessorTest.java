package com.sanyan.util;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TextProcessorTest {

    @Test
    void shouldStripChineseParenActions() {
        String text = "好啊（笑）那我们一起去吧";
        assertThat(TextProcessor.cleanAiReply(text)).isEqualTo("好啊那我们一起去吧");
    }

    @Test
    void shouldHandleEmptyOrNull() {
        assertThat(TextProcessor.cleanAiReply(null)).isEmpty();
        assertThat(TextProcessor.cleanAiReply("")).isEmpty();
    }

    @Test
    void shouldKeepNormalText() {
        String text = "今天去吃饭吗";
        assertThat(TextProcessor.cleanAiReply(text)).isEqualTo(text);
    }
}
