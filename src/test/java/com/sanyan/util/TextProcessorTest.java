package com.sanyan.util;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TextProcessorTest {

    @Test
    void extractActions_withSingleAction() {
        var result = TextProcessor.extract("你好呀（歪头微笑）今天怎么样？");
        assertThat(result.cleanText()).isEqualTo("你好呀今天怎么样？");
        assertThat(result.actions()).containsExactly("歪头微笑");
        assertThat(result.emotion()).isNull();
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
        assertThat(result.emotion()).isNull();
    }

    @Test
    void extractActions_actionAtStartAndEnd() {
        var result = TextProcessor.extract("（害羞地低头）谢谢你呀（开心地跳起来）");
        assertThat(result.cleanText()).isEqualTo("谢谢你呀");
        assertThat(result.actions()).containsExactly("害羞地低头", "开心地跳起来");
    }

    // === Emotion tag tests ===

    @Test
    void extractEmotion_happyTag() {
        var result = TextProcessor.extract("你好呀～[emotion:happy:3]");
        assertThat(result.cleanText()).isEqualTo("你好呀～");
        assertThat(result.emotion()).isNotNull();
        assertThat(result.emotion().type()).isEqualTo("happy");
        assertThat(result.emotion().scale()).isEqualTo(3);
    }

    @Test
    void extractEmotion_sadTag() {
        var result = TextProcessor.extract("别难过呀[emotion:sad:4]");
        assertThat(result.cleanText()).isEqualTo("别难过呀");
        assertThat(result.emotion().type()).isEqualTo("sad");
        assertThat(result.emotion().scale()).isEqualTo(4);
    }

    @Test
    void extractEmotion_withActionsAndEmotion() {
        var result = TextProcessor.extract("你好呀（开心地拍手）今天真开心！[emotion:happy:5]");
        assertThat(result.cleanText()).isEqualTo("你好呀今天真开心！");
        assertThat(result.actions()).containsExactly("开心地拍手");
        assertThat(result.emotion().type()).isEqualTo("happy");
        assertThat(result.emotion().scale()).isEqualTo(5);
    }

    @Test
    void extractEmotion_noTag() {
        var result = TextProcessor.extract("普通回复没有情感标记");
        assertThat(result.emotion()).isNull();
    }
}
