package com.sanyan.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TextProcessor {

    private static final Pattern ACTION_PATTERN = Pattern.compile("（([^）]+)）");
    // AI 在回复末尾附加的 TTS 合成风格指令（自然语言），传给豆包 TTS 的 context_texts[0]
    private static final Pattern TTS_STYLE_PATTERN = Pattern.compile("\\[tts_style:([^\\]]+)\\]");
    // 旧的情感枚举标签，保留匹配仅用于从文本里剔除，不再提取语义
    private static final Pattern LEGACY_EMOTION_PATTERN =
            Pattern.compile("\\[emotion:[^:\\]]+:\\d\\]");

    public record ExtractResult(String cleanText, List<String> actions, String ttsStyle) {}

    public static ExtractResult extract(String text) {
        if (text == null || text.isEmpty()) {
            return new ExtractResult("", List.of(), null);
        }

        // 1. Extract TTS style instruction（取最后一个匹配，允许前面的被覆盖）
        String ttsStyle = null;
        Matcher styleMatcher = TTS_STYLE_PATTERN.matcher(text);
        while (styleMatcher.find()) {
            ttsStyle = styleMatcher.group(1).trim();
        }
        String stripped = TTS_STYLE_PATTERN.matcher(text).replaceAll("");

        // 2. Strip legacy [emotion:xxx:N] tags（兼容历史数据，不再提取语义）
        stripped = LEGACY_EMOTION_PATTERN.matcher(stripped).replaceAll("").trim();

        // 3. Extract action descriptions（中文括号）
        List<String> actions = new ArrayList<>();
        StringBuffer sb = new StringBuffer();
        Matcher actionMatcher = ACTION_PATTERN.matcher(stripped);
        while (actionMatcher.find()) {
            actions.add(actionMatcher.group(1));
            actionMatcher.appendReplacement(sb, "");
        }
        actionMatcher.appendTail(sb);

        return new ExtractResult(sb.toString(), List.copyOf(actions), ttsStyle);
    }
}
