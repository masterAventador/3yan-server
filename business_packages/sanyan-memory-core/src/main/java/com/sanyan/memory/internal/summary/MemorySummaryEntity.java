package com.sanyan.memory.internal.summary;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 滚动摘要（V5 memory_summaries 表）：
 *
 * <p>每 N 条消息后台异步生成的一段对话纪要，按 (user_id, character_id) 分组，按
 * created_at 倒序拼进 LLM 上下文。schema 由 V5__init_memory_summaries.sql 维护，
 * 本 Entity 字段映射须与该 migration 严格对齐——V5MigrationIT（bootstrap 模块）
 * 守护字段类型 / nullability / 索引；本 Entity 同步的字段定义在此。
 *
 * <p>{@code @PrePersist} 兜底：应用层未显式赋值 {@code createdAt} 时用 {@link Instant#now()}
 * 填充，等价于 V5 schema 里 {@code DEFAULT NOW()}。两层保护：DB 默认值 + Entity 钩子，
 * 避免任一层失效导致 NOT NULL 违反。
 */
@Entity
@Table(name = "memory_summaries")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MemorySummaryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "character_id", nullable = false)
    private Long characterId;

    /** 本段摘要覆盖的消息区间起点 message_id（nullable：早期没有边界信息时允许空）。 */
    @Column(name = "period_start_message_id")
    private Long periodStartMessageId;

    /** 本段摘要覆盖的消息区间终点 message_id（nullable：理由同上）。 */
    @Column(name = "period_end_message_id")
    private Long periodEndMessageId;

    @Column(name = "summary_text", nullable = false, columnDefinition = "TEXT")
    private String summaryText;

    /** 本段摘要覆盖的消息条数（生成时由 SummaryService 写入）。 */
    @Column(name = "message_count", nullable = false)
    private Integer messageCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
