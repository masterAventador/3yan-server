package com.sanyan.push.dto;

/** 推送结果。本期 status 可为 PENDING（占位未实推）。 */
public record PushResult(String status, String detail) {
    public static PushResult pending(String detail) { return new PushResult("PENDING", detail); }
    public static PushResult sent()                 { return new PushResult("SENT", null); }
    public static PushResult failed(String detail)  { return new PushResult("FAILED", detail); }
}
