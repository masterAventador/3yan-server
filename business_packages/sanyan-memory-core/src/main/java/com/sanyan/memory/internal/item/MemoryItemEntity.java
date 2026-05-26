package com.sanyan.memory.internal.item;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * 结构化记忆条目（V9 memory_item 表，Plan 4）。与 memory_profiles.summary_text（宏观画像）互补：
 * 存一条条具体的、带时间的、日后值得主动提起的点（spec §4.1）。无外键。
 * kind/status 用 @Enumerated(STRING) 存大写 name，与 V9 CHECK 约束对齐。
 */
@Data
@Entity
@Table(name = "memory_item")
public class MemoryItemEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "character_id", nullable = false)
    private Long characterId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MemoryItemKind kind;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    /** 该条记忆该"冒出来"的时间（PLAN_EVENT=事件当天 9:00；EMOTION=次日 9:00）。 */
    @Column(name = "salient_at", nullable = false)
    private Instant salientAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MemoryItemStatus status = MemoryItemStatus.PENDING;

    @Column(name = "source_message_id")
    private Long sourceMessageId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
