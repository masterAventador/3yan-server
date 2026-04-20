package com.sanyan.util;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * doubao-seed-character 模型自带动作/神态描述，代码负责全部剥除（产品上不展示）。
 * 默认用中文全角 （...），偶发 rogue 到半角方括号 [...] 或星号 *...*。
 */
class TextProcessorTest {

    // ========== 动作剥除 ==========

    @Test
    void stripsFullwidthParenAction() {
        assertThat(TextProcessor.extract("你好呀（歪头微笑）今天怎么样？").cleanText())
                .isEqualTo("你好呀今天怎么样？");
    }

    @Test
    void stripsMultipleFullwidthParenActions() {
        assertThat(TextProcessor.extract("嗯（点头）我知道了（双手抱胸）").cleanText())
                .isEqualTo("嗯我知道了");
    }

    @Test
    void stripsHalfwidthSquareBracketAction() {
        assertThat(TextProcessor.extract("嗯……心里呀，还有……[故意停顿，轻笑着]你猜？").cleanText())
                .isEqualTo("嗯……心里呀，还有……你猜？");
    }

    @Test
    void stripsAsteriskAction() {
        assertThat(TextProcessor.extract("让我想想*歪头*大概是这样。").cleanText())
                .isEqualTo("让我想想大概是这样。");
    }

    @Test
    void stripsMixedActions() {
        assertThat(TextProcessor.extract("哈哈哈（捂嘴笑）真的假的[惊讶]*挠头*？").cleanText())
                .isEqualTo("哈哈哈真的假的？");
    }

    @Test
    void stripsActionsAtStartAndEnd() {
        assertThat(TextProcessor.extract("（害羞地低头）谢谢你呀（开心地跳起来）").cleanText())
                .isEqualTo("谢谢你呀");
    }

    @Test
    void noActions_textUnchanged() {
        assertThat(TextProcessor.extract("普通的回复没有动作").cleanText())
                .isEqualTo("普通的回复没有动作");
    }

    @Test
    void doesNotStripHalfwidthRoundParen() {
        // 英文小括号在正文里合法（如 "(yes)"、"(2024)"），不能剥
        assertThat(TextProcessor.extract("这是(英文括号)不剥").cleanText())
                .isEqualTo("这是(英文括号)不剥");
    }

    @Test
    void emptyOrNull_returnsEmpty() {
        assertThat(TextProcessor.extract("").cleanText()).isEmpty();
        assertThat(TextProcessor.extract(null).cleanText()).isEmpty();
    }

    // ========== tts_style 标签提取 ==========

    @Test
    void extractsTtsStyle_simple() {
        var r = TextProcessor.extract("你好呀～[tts_style:用温柔的语气说]");
        assertThat(r.cleanText()).isEqualTo("你好呀～");
        assertThat(r.ttsStyle()).isEqualTo("用温柔的语气说");
    }

    @Test
    void extractsTtsStyle_arbitraryNaturalLanguage() {
        var r = TextProcessor.extract("委屈又故作坚强的话[tts_style:委屈但强装镇定，声音略微颤抖]");
        assertThat(r.cleanText()).isEqualTo("委屈又故作坚强的话");
        assertThat(r.ttsStyle()).isEqualTo("委屈但强装镇定，声音略微颤抖");
    }

    @Test
    void extractsTtsStyle_alongsideAction() {
        var r = TextProcessor.extract("你好呀（开心地拍手）今天真开心！[tts_style:用开心雀跃的语气说]");
        assertThat(r.cleanText()).isEqualTo("你好呀今天真开心！");
        assertThat(r.ttsStyle()).isEqualTo("用开心雀跃的语气说");
    }

    @Test
    void noTtsStyle_returnsNull() {
        assertThat(TextProcessor.extract("普通回复").ttsStyle()).isNull();
    }

    // ========== 旧的 [emotion:xxx:N] 兼容（剥除，不识别为 tts_style） ==========

    @Test
    void stripsLegacyEmotionTag() {
        var r = TextProcessor.extract("你好呀[emotion:happy:3]");
        assertThat(r.cleanText()).isEqualTo("你好呀");
        assertThat(r.ttsStyle()).isNull();
    }
}
