package com.sanyan.common.ws;

public final class WsEventType {
    public static final String PING = "ping";
    public static final String PONG = "pong";
    public static final String SEND_MESSAGE = "send_message";
    public static final String NEW_MESSAGE = "new_message";
    public static final String TYPING = "typing";
    public static final String ACK = "ack";
    public static final String SYNC = "sync";
    public static final String SYNC_RESULT = "sync_result";
    public static final String ERROR = "error";

    /**
     * AI 一轮回复全部气泡推送完毕的信号。后端在 {@code new_message} 序列结束后（成功路径）
     * 或异常路径下各发一次，关联回触发本轮的 user 消息 clientMsgId。
     * 客户端/dogfood 收到即可立刻判定"AI 回复结束"，无需基于"静默 Xs 没新事件"猜测。
     */
    public static final String TURN_COMPLETE = "turn_complete";

    private WsEventType() {}
}
