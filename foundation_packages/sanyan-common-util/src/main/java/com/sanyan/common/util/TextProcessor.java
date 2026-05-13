package com.sanyan.common.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

public final class TextProcessor {

    // doubao-seed-character 模型自带动作/神态描述，默认用中文全角括号 （...），剥除不展示
    private static final Pattern ACTION_PATTERN = Pattern.compile("（[^）]+）");
    // 按 [。！？\n]+ 切段，把分隔符保留在段尾（除了换行符吞掉）
    private static final Pattern BUBBLE_SPLIT_PATTERN = Pattern.compile("(?<=[。！？])|\\n+");

    private TextProcessor() {}

    public static String cleanAiReply(String text) {
        if (text == null || text.isEmpty()) return "";
        return ACTION_PATTERN.matcher(text).replaceAll("");
    }

    /**
     * 按句号/感叹号/问号/换行把 AI 回复切成多条气泡。空白段过滤；超过 maxBubbles 时尾段合并到最后一条。
     */
    public static List<String> splitIntoBubbles(String text, int maxBubbles) {
        if (text == null || text.isBlank()) return Collections.emptyList();

        List<String> segments = new ArrayList<>();
        for (String raw : BUBBLE_SPLIT_PATTERN.split(text)) {
            String trimmed = raw.trim();
            if (!trimmed.isEmpty()) segments.add(trimmed);
        }
        if (segments.isEmpty()) return Collections.emptyList();
        if (segments.size() <= maxBubbles) return segments;

        List<String> capped = new ArrayList<>(segments.subList(0, maxBubbles - 1));
        capped.add(String.join("", segments.subList(maxBubbles - 1, segments.size())));
        return capped;
    }
}
