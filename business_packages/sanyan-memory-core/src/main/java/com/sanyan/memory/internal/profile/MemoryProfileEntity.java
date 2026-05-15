package com.sanyan.memory.internal.profile;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 自然语言画像（V8 memory_profiles 表，Plan 2.5 重构）：
 *
 * <p>长期记忆「自然语言画像」通道：以 (user_id, character_id) 为复合主键，存储一段
 * 由 LLM 直接维护的用户画像段落（{@link #summaryText}）。
 *
 * <p>原 Plan 2 设计是结构化 slot schema（basic_info / preferences / events / emotion_line），
 * 但下游消费者就是 LLM，转结构化再转回 prose 是多余的。Plan 2.5 改为 "LLM 维护一段自然语言画像"，
 * 数据通道更直、少一次 LLM 调用。
 *
 * <p>schema 由 V8__memory_profile_free_form.sql 维护（在 V6 基础上 DROP profile_jsonb +
 * ADD summary_text TEXT NOT NULL DEFAULT ''）。
 *
 * <p>{@code @Version Integer version}：JPA 乐观锁标准用法。每次 UPDATE 时 Hibernate 自动
 * 检查 version 当前值 + 加 1；并发更新冲突时抛 {@link org.springframework.orm.ObjectOptimisticLockingFailureException}，
 * 由 {@link MemoryProfileRefreshService} 捕获并重试一次。
 *
 * <p>{@code @PrePersist} + {@code @PreUpdate} 兜底：应用层未显式赋值 {@code updatedAt} 时用
 * {@link Instant#now()} 填充，等价于 DB schema 的 {@code DEFAULT NOW()}。两层保护：DB 默认值 +
 * Entity 钩子，避免任一层失效导致 NOT NULL 违反。
 */
@Entity
@Table(name = "memory_profiles")
@IdClass(MemoryProfileId.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MemoryProfileEntity {

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Id
    @Column(name = "character_id", nullable = false)
    private Long characterId;

    /**
     * 自然语言画像段落，由 LLM 直接维护（{@link MemoryProfileRefreshService}）。
     *
     * <p>{@code NOT NULL DEFAULT ''}：首次插入或 LLM 输出 blank 时落空字符串，避免下游空指针；
     * {@link com.sanyan.memory.internal.orchestrator.MemoryContextBuilder} 拼 prompt 时用
     * {@code isBlank()} 过滤空内容。
     */
    @Column(name = "summary_text", nullable = false, columnDefinition = "TEXT")
    private String summaryText;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        if (updatedAt == null) {
            updatedAt = Instant.now();
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
