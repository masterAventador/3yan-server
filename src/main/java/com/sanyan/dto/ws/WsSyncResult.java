package com.sanyan.dto.ws;

import com.sanyan.dto.data.MessageData;
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
