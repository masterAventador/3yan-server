package com.sanyan.dto.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WsError {
    private final String type = WsEventType.ERROR;
    private String clientMsgId;
    private Long conversationId;
    private String message;
}
