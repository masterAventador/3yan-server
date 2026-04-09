package com.sanyan.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TextProcessor {

    private static final Pattern ACTION_PATTERN = Pattern.compile("（([^）]+)）");

    public record ExtractResult(String cleanText, List<String> actions) {}

    public static ExtractResult extract(String text) {
        if (text == null || text.isEmpty()) {
            return new ExtractResult("", List.of());
        }

        List<String> actions = new ArrayList<>();
        Matcher matcher = ACTION_PATTERN.matcher(text);
        while (matcher.find()) {
            actions.add(matcher.group(1));
        }

        String cleanText = ACTION_PATTERN.matcher(text).replaceAll("");
        return new ExtractResult(cleanText, actions);
    }
}
