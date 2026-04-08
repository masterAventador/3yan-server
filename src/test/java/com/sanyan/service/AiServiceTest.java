package com.sanyan.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AiServiceTest {

    @Test
    void shouldAssemblePromptWithTimeAndProfile() {
        AiService aiService = new AiService(null, null, null, null, null);
        String systemPrompt = "你是小晚";
        String profile = "用户叫小明，是程序员";
        String time = "2026年4月7日 周一 14:30";

        String assembled = aiService.assembleSystemPrompt(systemPrompt, time, profile);

        assertThat(assembled).contains("你是小晚");
        assertThat(assembled).contains("2026年4月7日");
        assertThat(assembled).contains("小明");
    }
}
