package com.sanyan.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CosServiceTest {

    @Test
    void buildCosUrl_returnsCorrectFormat() {
        String url = CosService.buildCosUrl("3yan-1258800826", "ap-beijing", "voice/1/100.mp3");
        assertThat(url).isEqualTo("https://3yan-1258800826.cos.ap-beijing.myqcloud.com/voice/1/100.mp3");
    }

    @Test
    void buildObjectKeyForAiVoice_returnsCorrectPath() {
        String key = CosService.buildAiVoiceKey(100L, 200L);
        assertThat(key).isEqualTo("voice/ai/100/200.mp3");
    }

    @Test
    void buildObjectKeyForUserVoice_returnsCorrectPath() {
        String key = CosService.buildUserVoiceKey(1L, "abc123");
        assertThat(key).startsWith("voice/user/1/");
        assertThat(key).endsWith("_abc123.m4a");
    }
}
