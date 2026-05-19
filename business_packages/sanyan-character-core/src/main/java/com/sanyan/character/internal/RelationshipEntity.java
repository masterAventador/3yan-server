package com.sanyan.character.internal;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 用户与 AI 角色的亲密关系实体，对应 V3 migration 创建的 relationships 表。
 *
 * <p>复合主键（user_id, character_id）通过 {@code @IdClass(RelationshipId.class)} 声明。
 * {@code @Version} 字段 version 实现乐观锁，每次 UPDATE 自动递增。
 */
@Data
@Entity
@Table(name = "relationships")
@IdClass(RelationshipId.class)
public class RelationshipEntity {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Id
    @Column(name = "character_id")
    private Long characterId;

    @Column(name = "intimacy_score", nullable = false)
    private Integer intimacyScore = 0;

    @Column(name = "current_stage", nullable = false)
    private Short currentStage = 0;

    @Version
    @Column(nullable = false)
    private Integer version = 0;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
