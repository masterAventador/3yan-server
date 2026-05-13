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

    private WsEventType() {}
}
