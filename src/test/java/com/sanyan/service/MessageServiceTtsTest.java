package com.sanyan.service;

import com.sanyan.util.TextProcessor;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MessageServiceTtsTest {

    @Test
    void ttsFlow_extractsActionsAndCleansText() {
        String aiReply = "当然可以呀（开心地拍手）我们来聊聊吧！";
        var result = TextProcessor.extract(aiReply);
        assertThat(result.cleanText()).isEqualTo("当然可以呀我们来聊聊吧！");
        assertThat(result.actions()).containsExactly("开心地拍手");
    }

    @Test
    void ttsFlow_noActions_textUnchanged() {
        String aiReply = "好的，我知道了~";
        var result = TextProcessor.extract(aiReply);
        assertThat(result.cleanText()).isEqualTo("好的，我知道了~");
        assertThat(result.actions()).isEmpty();
    }
}
