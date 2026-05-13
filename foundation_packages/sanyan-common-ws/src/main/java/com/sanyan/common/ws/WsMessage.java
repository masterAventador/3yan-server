package com.sanyan.common.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WsMessage {
    private String type;
    // send_message
    private String content;
    private String clientMsgId;
    // sync
    private Long lastMsgId;
}
