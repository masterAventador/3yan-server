package com.sanyan.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TextProcessor {

    private static final Pattern ACTION_PATTERN = Pattern.compile("（([^）]+)）");
    private static final Pattern EMOTION_PATTERN = Pattern.compile("\\[emotion:(\\w+):(\\d)\\]");

    public record ExtractResult(String cleanText, List<String> actions, EmotionTag emotion) {}

    public record EmotionTag(String type, int scale) {}

    public static ExtractResult extract(String text) {
        if (text == null || text.isEmpty()) {
            return new ExtractResult("", List.of(), null);
        }

        // 1. Extract emotion tag from end of text
        EmotionTag emotion = null;
        Matcher emotionMatcher = EMOTION_PATTERN.matcher(text);
        String textWithoutEmotion = text;
        while (emotionMatcher.find()) {
            emotion = new EmotionTag(emotionMatcher.group(1), Integer.parseInt(emotionMatcher.group(2)));
        }
        textWithoutEmotion = EMOTION_PATTERN.matcher(text).replaceAll("").trim();

        // 2. Extract action descriptions
        List<String> actions = new ArrayList<>();
        StringBuffer sb = new StringBuffer();
        Matcher actionMatcher = ACTION_PATTERN.matcher(textWithoutEmotion);
        while (actionMatcher.find()) {
            actions.add(actionMatcher.group(1));
            actionMatcher.appendReplacement(sb, "");
        }
        actionMatcher.appendTail(sb);

        return new ExtractResult(sb.toString(), List.copyOf(actions), emotion);
    }
}
