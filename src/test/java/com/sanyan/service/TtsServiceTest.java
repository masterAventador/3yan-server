package com.sanyan.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TtsServiceTest {

    @Test
    void buildRequestBody_withoutTtsStyle() {
        String body = TtsService.buildRequestBody(
                "zh_female_vv_uranus_bigtts", "你好呀", null);
        assertThat(body).contains("\"speaker\":\"zh_female_vv_uranus_bigtts\"");
        assertThat(body).contains("\"text\":\"你好呀\"");
        assertThat(body).contains("\"format\":\"mp3\"");
        assertThat(body).contains("\"sample_rate\":24000");
        assertThat(body).doesNotContain("\"emotion\"");
        assertThat(body).doesNotContain("\"context_texts\"");
    }

    @Test
    void buildRequestBody_withTtsStyle() {
        String body = TtsService.buildRequestBody(
                "zh_female_vv_uranus_bigtts", "别难过呀", "用温柔心疼的语气慢慢说");
        // 豆包 TTS V3 契约：additions 必须是转义的 JSON 字符串，不是嵌套对象；
        // context_texts 是 array，第一个元素是自然语言风格指令
        assertThat(body).contains(
                "\"additions\":\"{\\\"context_texts\\\":[\\\"用温柔心疼的语气慢慢说\\\"]}\"");
    }

    @Test
    void buildRequestBody_emptyTtsStyle_notIncluded() {
        String body = TtsService.buildRequestBody(
                "zh_female_vv_uranus_bigtts", "你好呀", "");
        assertThat(body).doesNotContain("\"context_texts\"");
        assertThat(body).doesNotContain("\"additions\"");
    }

    @Test
    void buildRequestBody_blankTtsStyle_notIncluded() {
        String body = TtsService.buildRequestBody(
                "zh_female_vv_uranus_bigtts", "你好呀", "   ");
        assertThat(body).doesNotContain("\"context_texts\"");
        assertThat(body).doesNotContain("\"additions\"");
    }

    @Test
    void buildRequestBody_noEmotionField() {
        // emotion 和 emotion_scale 字段已废弃，任何调用都不应该生成这两个字段
        String body = TtsService.buildRequestBody(
                "zh_female_vv_uranus_bigtts", "你好呀", "用开心的语气");
        assertThat(body).doesNotContain("\"emotion\"");
        assertThat(body).doesNotContain("\"emotion_scale\"");
    }
}
