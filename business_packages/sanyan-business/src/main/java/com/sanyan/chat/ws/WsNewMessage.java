package com.sanyan.chat.ws;

import com.sanyan.chat.web.MessageData;
import com.sanyan.common.ws.WsEventType;
import lombok.Data;

@Data
public class WsNewMessage {
    private final String type = WsEventType.NEW_MESSAGE;
    private MessageData message;

    public WsNewMessage(MessageData message) {
        this.message = message;
    }
}
