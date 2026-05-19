package com.sanyan.character.internal.intimacy;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * 亲密度变更审计日志，对应 V4 migration 创建的 intimacy_logs 表。
 *
 * <p>审计表故意不设 FK 约束（解耦 user_id / character_id），
 * 因此 JPA 实体也不声明 @ManyToOne 关联，仅保存裸 ID 列。
 */
@Data
@Entity
@Table(name = "intimacy_logs")
public class IntimacyLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "character_id", nullable = false)
    private Long characterId;

    @Column(nullable = false)
    private Integer delta;

    @Column(nullable = false, length = 60)
    private String reason;

    @Column(columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String metadata;

    @Column(name = "new_score", nullable = false)
    private Integer newScore;

    @Column(name = "new_stage", nullable = false)
    private Short newStage;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
