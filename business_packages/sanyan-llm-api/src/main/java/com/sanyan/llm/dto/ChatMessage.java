package com.sanyan.llm.dto;

/**
 * OpenAI 兼容的 chat message。role 取值 {@code "system" / "user" / "assistant"}。
 *
 * <p>各业务方（chat 域、memory 域的 summary/profile）独立拼装 {@code List<ChatMessage>}，
 * 然后传给 {@link com.sanyan.llm.LlmApi#chat}。
 *
 * <p>静态工厂方法 {@link #system} / {@link #user} / {@link #assistant} 简化常见拼装语法，
 * 避免调用方手敲字符串。
 */
public record ChatMessage(String role, String content) {

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content);
    }
}
