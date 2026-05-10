package com.sanyan.dto.ws;

import lombok.Data;

@Data
public class WsTyping {
    private final String type = WsEventType.TYPING;
}
