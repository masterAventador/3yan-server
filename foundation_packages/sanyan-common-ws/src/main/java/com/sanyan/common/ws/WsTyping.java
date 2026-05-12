package com.sanyan.common.ws;

import lombok.Data;

@Data
public class WsTyping {
    private final String type = WsEventType.TYPING;
}
