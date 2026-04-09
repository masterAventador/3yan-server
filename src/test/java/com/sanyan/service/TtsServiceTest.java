package com.sanyan.service;

import org.junit.jupiter.api.Test;
import java.util.List;
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
        assertThat(body).doesNotContain("\"emotion\"");
    }

    @Test
    void buildRequestBody_withHappyAction_setsEmotion() {
        String body = TtsService.buildRequestBody(
                "7231248180", "test_token", "volcano_tts",
                "zh_female_vv_uranus_bigtts", "你好呀",
                List.of("开心地拍手"));
        assertThat(body).contains("\"text\":\"你好呀\"");
        assertThat(body).doesNotContain("开心地拍手");
        assertThat(body).contains("\"emotion\":\"happy\"");
        assertThat(body).contains("\"emotion_strength\":0.8");
    }

    @Test
    void buildRequestBody_withSadAction_setsEmotion() {
        String body = TtsService.buildRequestBody(
                "7231248180", "test_token", "volcano_tts",
                "zh_female_vv_uranus_bigtts", "别难过",
                List.of("叹气"));
        assertThat(body).contains("\"emotion\":\"sad\"");
    }

    @Test
    void buildRequestBody_withUnknownAction_noEmotion() {
        String body = TtsService.buildRequestBody(
                "7231248180", "test_token", "volcano_tts",
                "zh_female_vv_uranus_bigtts", "你好呀",
                List.of("歪头"));
        assertThat(body).doesNotContain("\"emotion\"");
    }

    @Test
    void mapActionsToEmotion_happy() {
        assertThat(TtsService.mapActionsToEmotion(List.of("开心地跳起来"))).isEqualTo("happy");
        assertThat(TtsService.mapActionsToEmotion(List.of("微笑着点头"))).isEqualTo("happy");
    }

    @Test
    void mapActionsToEmotion_sad() {
        assertThat(TtsService.mapActionsToEmotion(List.of("难过地低头"))).isEqualTo("sad");
        assertThat(TtsService.mapActionsToEmotion(List.of("委屈巴巴"))).isEqualTo("sad");
    }

    @Test
    void mapActionsToEmotion_angry() {
        assertThat(TtsService.mapActionsToEmotion(List.of("生气地皱眉"))).isEqualTo("angry");
    }

    @Test
    void mapActionsToEmotion_null_whenNoMatch() {
        assertThat(TtsService.mapActionsToEmotion(List.of("歪头", "双手抱胸"))).isNull();
        assertThat(TtsService.mapActionsToEmotion(null)).isNull();
        assertThat(TtsService.mapActionsToEmotion(List.of())).isNull();
    }
}
