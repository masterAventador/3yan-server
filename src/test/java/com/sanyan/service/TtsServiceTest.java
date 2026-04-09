package com.sanyan.service;

import com.sanyan.util.TextProcessor;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TtsServiceTest {

    @Test
    void buildRequestBody_withoutEmotion() {
        String body = TtsService.buildRequestBody(
                "7231248180", "test_token", "volcano_tts",
                "zh_female_vv_uranus_bigtts", "你好呀", null);
        assertThat(body).contains("\"appid\":\"7231248180\"");
        assertThat(body).contains("\"token\":\"test_token\"");
        assertThat(body).contains("\"voice_type\":\"zh_female_vv_uranus_bigtts\"");
        assertThat(body).contains("\"text\":\"你好呀\"");
        assertThat(body).contains("\"encoding\":\"mp3\"");
        assertThat(body).doesNotContain("\"emotion\"");
        assertThat(body).doesNotContain("\"enable_emotion\"");
    }

    @Test
    void buildRequestBody_withEmotion() {
        var emotion = new TextProcessor.EmotionTag("happy", 4);
        String body = TtsService.buildRequestBody(
                "7231248180", "test_token", "volcano_tts",
                "zh_female_vv_uranus_bigtts", "你好呀", emotion);
        assertThat(body).contains("\"text\":\"你好呀\"");
        assertThat(body).contains("\"enable_emotion\":true");
        assertThat(body).contains("\"emotion\":\"happy\"");
        assertThat(body).contains("\"emotion_scale\":4.0");
    }

    @Test
    void buildRequestBody_withSadEmotion() {
        var emotion = new TextProcessor.EmotionTag("sad", 2);
        String body = TtsService.buildRequestBody(
                "7231248180", "test_token", "volcano_tts",
                "zh_female_vv_uranus_bigtts", "别难过", emotion);
        assertThat(body).contains("\"emotion\":\"sad\"");
        assertThat(body).contains("\"emotion_scale\":2.0");
    }
}
