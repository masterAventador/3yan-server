package com.sanyan.dto.ws;

import com.sanyan.dto.data.MessageData;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class WsSyncResult {
    private final String type = "sync_result";
    private Map<Long, List<MessageData>> conversations;

    public WsSyncResult(Map<Long, List<MessageData>> conversations) {
        this.conversations = conversations;
    }
}
