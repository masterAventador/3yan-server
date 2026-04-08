package com.sanyan.dto.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WsMessage {
    private String type;
    // send_message fields
    private Long conversationId;
    private String contentType;
    private String content;
    private String clientMsgId;
    // sync fields
    private Long lastMsgId;
}
