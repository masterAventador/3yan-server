package com.sanyan.util;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TextProcessorTest {

    @Test
    void extractActions_withSingleAction() {
        var result = TextProcessor.extract("你好呀（歪头微笑）今天怎么样？");
        assertThat(result.cleanText()).isEqualTo("你好呀今天怎么样？");
        assertThat(result.actions()).containsExactly("歪头微笑");
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
    }

    @Test
    void extractActions_actionAtStartAndEnd() {
        var result = TextProcessor.extract("（害羞地低头）谢谢你呀（开心地跳起来）");
        assertThat(result.cleanText()).isEqualTo("谢谢你呀");
        assertThat(result.actions()).containsExactly("害羞地低头", "开心地跳起来");
    }
}
