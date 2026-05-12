package com.sanyan.chat.ws;

import com.sanyan.common.ws.WsEventType;
import lombok.Data;

@Data
public class WsAck {
    private final String type = WsEventType.ACK;
    private String clientMsgId;
    /**
     * 服务端落库后的真实 message id。客户端用它替换发送时的本地临时 id，
     * 之后 cold start sync 拉历史不会因 id 不同造成同一条 user 消息显示两遍。
     */
    private Long serverMsgId;

    public WsAck(String clientMsgId, Long serverMsgId) {
        this.clientMsgId = clientMsgId;
        this.serverMsgId = serverMsgId;
    }
}
