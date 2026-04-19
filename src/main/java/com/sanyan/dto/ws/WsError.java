package com.sanyan.dto.ws;

import lombok.Data;

@Data
public class WsError {
    private final String type = WsEventType.ERROR;
    private String clientMsgId;
    private Long conversationId;
    private String message;

    public WsError(String clientMsgId, Long conversationId, String message) {
        this.clientMsgId = clientMsgId;
        this.conversationId = conversationId;
        this.message = message;
    }
}
