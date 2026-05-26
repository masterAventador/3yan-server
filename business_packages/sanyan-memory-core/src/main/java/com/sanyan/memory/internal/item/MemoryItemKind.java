package com.sanyan.memory.internal.item;

/**
 * 结构化记忆条目分类（spec §4.1）。@Enumerated STRING 存大写 name。
 * PLAN_EVENT=有时间的事（c 场景）；EMOTION=情绪（d 场景）；PROMISE=承诺（本期仅留存不排期）。
 */
public enum MemoryItemKind {
    PLAN_EVENT,
    EMOTION,
    PROMISE
}
