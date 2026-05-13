package com.sanyan.chat.ws;

import com.sanyan.chat.web.MessageData;
import com.sanyan.common.ws.WsEventType;
import lombok.Data;

import java.util.List;

@Data
public class WsSyncResult {
    private final String type = WsEventType.SYNC_RESULT;
    private List<MessageData> messages;

    public WsSyncResult(List<MessageData> messages) {
        this.messages = messages;
    }
}
