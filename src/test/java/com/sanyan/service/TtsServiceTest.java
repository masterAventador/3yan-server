package com.sanyan.service;

import com.sanyan.util.TextProcessor;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class TtsServiceTest {

    @Test
    void buildRequestBody_withoutEmotionOrContext() {
        String body = TtsService.buildRequestBody(
                "zh_female_vv_uranus_bigtts", "你好呀", null, null);
        assertThat(body).contains("\"speaker\":\"zh_female_vv_uranus_bigtts\"");
        assertThat(body).contains("\"text\":\"你好呀\"");
        assertThat(body).contains("\"format\":\"mp3\"");
        assertThat(body).contains("\"sample_rate\":24000");
        assertThat(body).doesNotContain("\"emotion\"");
        assertThat(body).doesNotContain("\"context_texts\"");
    }

    @Test
    void buildRequestBody_withEmotion() {
        var emotion = new TextProcessor.EmotionTag("happy", 4);
        String body = TtsService.buildRequestBody(
                "zh_female_vv_uranus_bigtts", "你好呀", emotion, null);
        assertThat(body).contains("\"emotion\":\"happy\"");
        assertThat(body).contains("\"emotion_scale\":4");
    }

    @Test
    void buildRequestBody_withContextTexts() {
        String body = TtsService.buildRequestBody(
                "zh_female_vv_uranus_bigtts", "别难过呀", null,
                List.of("我今天心情不好"));
        assertThat(body).contains("\"context_texts\":[\"我今天心情不好\"]");
    }

    @Test
    void buildRequestBody_withEmotionAndContext() {
        var emotion = new TextProcessor.EmotionTag("sad", 3);
        String body = TtsService.buildRequestBody(
                "zh_female_vv_uranus_bigtts", "没事的", emotion,
                List.of("我失恋了"));
        assertThat(body).contains("\"emotion\":\"sad\"");
        assertThat(body).contains("\"emotion_scale\":3");
        assertThat(body).contains("\"context_texts\":[\"我失恋了\"]");
        assertThat(body).contains("\"text\":\"没事的\"");
    }
}
