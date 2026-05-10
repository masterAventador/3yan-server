package com.sanyan.dto.ws;

import com.sanyan.dto.data.MessageData;
import lombok.Data;

@Data
public class WsNewMessage {
    private final String type = WsEventType.NEW_MESSAGE;
    private MessageData message;

    public WsNewMessage(MessageData message) {
        this.message = message;
    }
}
