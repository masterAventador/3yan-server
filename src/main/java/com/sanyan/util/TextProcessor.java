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
        StringBuffer sb = new StringBuffer();
        Matcher matcher = ACTION_PATTERN.matcher(text);
        while (matcher.find()) {
            actions.add(matcher.group(1));
            matcher.appendReplacement(sb, "");
        }
        matcher.appendTail(sb);
        return new ExtractResult(sb.toString(), List.copyOf(actions));
    }
}
