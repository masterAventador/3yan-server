package com.sanyan.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TextProcessor {

    // AI 在回复末尾附加的 TTS 合成风格指令（自然语言），传给豆包 TTS 的 context_texts[0]
    private static final Pattern TTS_STYLE_PATTERN = Pattern.compile("\\[tts_style:([^\\]]+)\\]");
    // 旧的情感枚举标签，保留匹配仅用于从文本里剔除，不再提取语义
    private static final Pattern LEGACY_EMOTION_PATTERN =
            Pattern.compile("\\[emotion:[^:\\]]+:\\d\\]");
    // doubao-seed-character 模型自带动作/神态描述，默认用中文全角括号 （...）。
    // 产品上全部剥除不展示。只剥中文全角——半角方括号 [...]、星号 *...*、
    // 书名号《》 等在正文里都可能合法（代码、标签、书名），靠正则兜底会误伤。
    // 模型偶发 rogue 到其他括号的情况由 system prompt 约束（明确告知只输出
    // [tts_style:...] 这一种标签），而不是在代码里兜底。
    private static final Pattern ACTION_PATTERN = Pattern.compile("（[^）]+）");

    public record ExtractResult(String cleanText, String ttsStyle) {}

    public static ExtractResult extract(String text) {
        if (text == null || text.isEmpty()) {
            return new ExtractResult("", null);
        }

        // 1. Extract TTS style instruction（取最后一个匹配，允许前面的被覆盖）
        String ttsStyle = null;
        Matcher styleMatcher = TTS_STYLE_PATTERN.matcher(text);
        while (styleMatcher.find()) {
            ttsStyle = styleMatcher.group(1).trim();
        }
        String stripped = TTS_STYLE_PATTERN.matcher(text).replaceAll("");

        // 2. Strip legacy [emotion:xxx:N] tags
        stripped = LEGACY_EMOTION_PATTERN.matcher(stripped).replaceAll("");

        // 3. Strip action/expression descriptions
        stripped = ACTION_PATTERN.matcher(stripped).replaceAll("");

        return new ExtractResult(stripped, ttsStyle);
    }
}
