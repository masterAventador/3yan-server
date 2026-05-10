package com.sanyan.util;

import java.util.regex.Pattern;

public class TextProcessor {

    // doubao-seed-character 模型自带动作/神态描述，默认用中文全角括号 （...），剥除不展示
    private static final Pattern ACTION_PATTERN = Pattern.compile("（[^）]+）");

    public static String cleanAiReply(String text) {
        if (text == null || text.isEmpty()) return "";
        return ACTION_PATTERN.matcher(text).replaceAll("");
    }
}
