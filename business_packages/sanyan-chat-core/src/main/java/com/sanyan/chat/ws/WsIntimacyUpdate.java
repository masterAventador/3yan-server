package com.sanyan.chat.ws;

/**
 * WS 推送帧：亲密度变更通知。
 *
 * @param type   固定 "intimacy_update"
 * @param score  变更后亲密度总分
 * @param delta  本次变更量（可为负）
 * @param reason 变更原因标识（如 "MESSAGE_SENT"）
 */
public record WsIntimacyUpdate(String type, int score, int delta, String reason) {}
