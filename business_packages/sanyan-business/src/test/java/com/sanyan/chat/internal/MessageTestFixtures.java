package com.sanyan.chat.internal;

import com.sanyan.chat.ws.SenderType;

/**
 * MessageEntity Object Mother (java-backend-business-layer.md §5.2)。
 * 测试中需要 MessageEntity 时一律通过这里构造，不允许裸 new。
 * 注意：senderType 字段是 String（持久化字段），值通过 SenderType.USER / SenderType.AI 常量提供。
 */
public final class MessageTestFixtures {

    private MessageTestFixtures() {}

    public static MessageEntity validUserMessage() {
        MessageEntity m = new MessageEntity();
        m.setUserId(1L);
        m.setSenderType(SenderType.USER);
        m.setContent("你好");
        return m;
    }

    public static MessageEntity validAiMessage() {
        MessageEntity m = new MessageEntity();
        m.setUserId(1L);
        m.setSenderType(SenderType.AI);
        m.setContent("你好呀");
        return m;
    }

    public static MessageEntity messageWithContent(String content) {
        MessageEntity m = validUserMessage();
        m.setContent(content);
        return m;
    }
}
