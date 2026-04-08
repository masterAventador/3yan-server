package com.sanyan.dto.ws;

import com.sanyan.dto.data.MessageData;
import lombok.Data;

@Data
public class WsNewMessage {
    private final String type = "new_message";
    private Long conversationId;
    private MessageData message;

    public WsNewMessage(MessageData message) {
        this.message = message;
        this.conversationId = message.getConversationId();
    }
}
