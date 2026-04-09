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
    void buildObjectKey_returnsCorrectPath() {
        String key = CosService.buildObjectKey(42L, 100L);
        assertThat(key).isEqualTo("voice/42/100.mp3");
    }
}
