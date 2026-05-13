package com.sanyan.common.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TextProcessorTest {

    @Test
    void shouldStripChineseParenActions() {
        String text = "好啊（笑）那我们一起去吧";
        assertThat(TextProcessor.cleanAiReply(text)).isEqualTo("好啊那我们一起去吧");
    }

    @Test
    void shouldHandleEmptyOrNull() {
        assertThat(TextProcessor.cleanAiReply(null)).isEmpty();
        assertThat(TextProcessor.cleanAiReply("")).isEmpty();
    }

    @Test
    void shouldKeepNormalText() {
        String text = "今天去吃饭吗";
        assertThat(TextProcessor.cleanAiReply(text)).isEqualTo(text);
    }

    @Test
    void splitIntoBubbles_singleSentenceNoPunctuation_returnsOneBubble() {
        List<String> bubbles = TextProcessor.splitIntoBubbles("今天好开心", 4);
        assertThat(bubbles).containsExactly("今天好开心");
    }

    @Test
    void splitIntoBubbles_multipleSentencesByPunctuation_splitsIntoMultipleBubbles() {
        List<String> bubbles = TextProcessor.splitIntoBubbles("我刚撸完猫。还在听歌呢！你呢？", 4);
        assertThat(bubbles).containsExactly("我刚撸完猫。", "还在听歌呢！", "你呢？");
    }

    @Test
    void splitIntoBubbles_byNewline_splitsIntoMultipleBubbles() {
        List<String> bubbles = TextProcessor.splitIntoBubbles("第一段\n第二段\n第三段", 4);
        assertThat(bubbles).containsExactly("第一段", "第二段", "第三段");
    }

    @Test
    void splitIntoBubbles_emptyAndBlankSegmentsFiltered() {
        List<String> bubbles = TextProcessor.splitIntoBubbles("第一段。\n\n   \n第二段。", 4);
        assertThat(bubbles).containsExactly("第一段。", "第二段。");
    }

    @Test
    void splitIntoBubbles_exceedingMaxCount_collapsesTailIntoLast() {
        List<String> bubbles = TextProcessor.splitIntoBubbles("A。B。C。D。E。F。", 4);
        assertThat(bubbles).hasSize(4);
        assertThat(bubbles.get(0)).isEqualTo("A。");
        assertThat(bubbles.get(1)).isEqualTo("B。");
        assertThat(bubbles.get(2)).isEqualTo("C。");
        assertThat(bubbles.get(3)).isEqualTo("D。E。F。");
    }

    @Test
    void splitIntoBubbles_nullOrEmptyOrBlank_returnsEmptyList() {
        assertThat(TextProcessor.splitIntoBubbles(null, 4)).isEmpty();
        assertThat(TextProcessor.splitIntoBubbles("", 4)).isEmpty();
        assertThat(TextProcessor.splitIntoBubbles("   \n  ", 4)).isEmpty();
    }

    @Test
    void splitIntoBubbles_trimsWhitespaceWithinSegments() {
        List<String> bubbles = TextProcessor.splitIntoBubbles("  第一段。   第二段。  ", 4);
        assertThat(bubbles).containsExactly("第一段。", "第二段。");
    }
}
