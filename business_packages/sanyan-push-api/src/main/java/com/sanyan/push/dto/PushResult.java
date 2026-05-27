package com.sanyan.push.dto;

/** 推送结果。本期 status 可为 PENDING（占位未实推）。 */
public record PushResult(String status, String detail) {
    private static final String STATUS_FAILED = "FAILED";

    public static PushResult pending(String detail) { return new PushResult("PENDING", detail); }
    public static PushResult sent()                 { return new PushResult("SENT", null); }
    public static PushResult failed(String detail)  { return new PushResult(STATUS_FAILED, detail); }

    /** 是否为失败状态（status=FAILED）。单点判断，调用方不再各自硬编码 "FAILED"。 */
    public boolean isFailed() { return STATUS_FAILED.equals(status); }
}
