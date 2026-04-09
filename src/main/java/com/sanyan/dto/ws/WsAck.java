package com.sanyan.dto.ws;

import lombok.Data;

@Data
public class WsAck {
    private final String type = WsEventType.ACK;
    private String clientMsgId;

    public WsAck(String clientMsgId) {
        this.clientMsgId = clientMsgId;
    }
}
