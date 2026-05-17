package com.sanyan.chat.ws;

/**
 * WS 推送帧：关系阶段跃迁通知。
 *
 * @param type 固定 "stage_transition"
 * @param from 跃迁前阶段编号
 * @param to   跃迁后阶段编号
 */
public record WsStageTransition(String type, int from, int to) {}
