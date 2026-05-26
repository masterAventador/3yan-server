package com.sanyan.memory.internal.item;

/**
 * 记忆条目状态。PENDING=待处理；DONE=已被主动消息消费；EXPIRED=预留。
 */
public enum MemoryItemStatus {
    PENDING,
    DONE,
    EXPIRED
}
