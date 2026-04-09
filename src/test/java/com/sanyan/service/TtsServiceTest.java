package com.sanyan.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TtsServiceTest {

    @Test
    void buildRequestBody_withoutActions() {
        String body = TtsService.buildRequestBody(
                "7231248180", "test_token", "volcano_tts",
                "zh_female_vv_uranus_bigtts", "你好呀", null);
        assertThat(body).contains("\"appid\":\"7231248180\"");
        assertThat(body).contains("\"token\":\"test_token\"");
        assertThat(body).contains("\"voice_type\":\"zh_female_vv_uranus_bigtts\"");
        assertThat(body).contains("\"text\":\"你好呀\"");
        assertThat(body).contains("\"encoding\":\"mp3\"");
    }

    @Test
    void buildRequestBody_withActions_onlySendsCleanText() {
        String body = TtsService.buildRequestBody(
                "7231248180", "test_token", "volcano_tts",
                "zh_female_vv_uranus_bigtts", "你好呀",
                java.util.List.of("害羞地低头"));
        // Actions should NOT be in the text sent to TTS
        assertThat(body).contains("\"text\":\"你好呀\"");
        assertThat(body).doesNotContain("害羞地低头");
    }
}
