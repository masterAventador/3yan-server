package com.sanyan.util;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TextProcessorTest {

    @Test
    void extractActions_withSingleAction() {
        var result = TextProcessor.extract("你好呀（歪头微笑）今天怎么样？");
        assertThat(result.cleanText()).isEqualTo("你好呀今天怎么样？");
        assertThat(result.actions()).containsExactly("歪头微笑");
        assertThat(result.ttsStyle()).isNull();
    }

    @Test
    void extractActions_withMultipleActions() {
        var result = TextProcessor.extract("嗯（点头）我知道了（双手抱胸）");
        assertThat(result.cleanText()).isEqualTo("嗯我知道了");
        assertThat(result.actions()).containsExactly("点头", "双手抱胸");
    }

    @Test
    void extractActions_withNoActions() {
        var result = TextProcessor.extract("普通的回复没有动作");
        assertThat(result.cleanText()).isEqualTo("普通的回复没有动作");
        assertThat(result.actions()).isEmpty();
    }

    @Test
    void extractActions_withEnglishParentheses_shouldNotExtract() {
        var result = TextProcessor.extract("这是(英文括号)不提取");
        assertThat(result.cleanText()).isEqualTo("这是(英文括号)不提取");
        assertThat(result.actions()).isEmpty();
    }

    @Test
    void extractActions_withEmptyInput() {
        var result = TextProcessor.extract("");
        assertThat(result.cleanText()).isEmpty();
        assertThat(result.actions()).isEmpty();
        assertThat(result.ttsStyle()).isNull();
    }

    @Test
    void extractActions_actionAtStartAndEnd() {
        var result = TextProcessor.extract("（害羞地低头）谢谢你呀（开心地跳起来）");
        assertThat(result.cleanText()).isEqualTo("谢谢你呀");
        assertThat(result.actions()).containsExactly("害羞地低头", "开心地跳起来");
    }

    // === tts_style tag tests ===

    @Test
    void extractTtsStyle_simpleInstruction() {
        var result = TextProcessor.extract("你好呀～[tts_style:用温柔的语气说]");
        assertThat(result.cleanText()).isEqualTo("你好呀～");
        assertThat(result.ttsStyle()).isEqualTo("用温柔的语气说");
    }

    @Test
    void extractTtsStyle_withActionsAndStyle() {
        var result = TextProcessor.extract("你好呀（开心地拍手）今天真开心！[tts_style:用开心雀跃的语气说]");
        assertThat(result.cleanText()).isEqualTo("你好呀今天真开心！");
        assertThat(result.actions()).containsExactly("开心地拍手");
        assertThat(result.ttsStyle()).isEqualTo("用开心雀跃的语气说");
    }

    @Test
    void extractTtsStyle_noTag() {
        var result = TextProcessor.extract("普通回复没有 tts_style 标记");
        assertThat(result.ttsStyle()).isNull();
    }

    @Test
    void extractTtsStyle_arbitraryNaturalLanguage() {
        // 任意自然语言描述都应该被支持（不受枚举限制）
        var result = TextProcessor.extract("委屈又故作坚强的话[tts_style:委屈但强装镇定，声音略微颤抖]");
        assertThat(result.cleanText()).isEqualTo("委屈又故作坚强的话");
        assertThat(result.ttsStyle()).isEqualTo("委屈但强装镇定，声音略微颤抖");
    }

    @Test
    void extractTtsStyle_ignoresLegacyEmotionTag() {
        // 旧的 [emotion:xxx:N] 标签不应该再被识别，但也不能把它留在 cleanText 里影响 TTS 合成
        var result = TextProcessor.extract("你好呀[emotion:happy:3]");
        assertThat(result.cleanText()).isEqualTo("你好呀");
        assertThat(result.ttsStyle()).isNull();
    }
}
