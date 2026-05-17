package com.sanyan.chat.ws;

import com.sanyan.common.ws.WsEventType;
import lombok.Data;

@Data
public class WsError {
    private final String type = WsEventType.ERROR;
    private String clientMsgId;
    private String message;

    public WsError(String clientMsgId, String message) {
        this.clientMsgId = clientMsgId;
        this.message = message;
    }
}
