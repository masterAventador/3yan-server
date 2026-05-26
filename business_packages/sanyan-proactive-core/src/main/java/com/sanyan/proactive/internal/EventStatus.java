package com.sanyan.proactive.internal;

/** 主动事件调度状态（DB events_pending.status 存 name() 大写）。 */
public enum EventStatus { SCHEDULED, PROCESSING, SENT, FAILED, CANCELLED }
