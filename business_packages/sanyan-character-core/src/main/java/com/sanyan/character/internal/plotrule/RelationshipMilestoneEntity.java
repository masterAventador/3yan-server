package com.sanyan.character.internal.plotrule;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 关系里程碑记录实体，对应 V5 migration 创建的 relationship_milestones 表。
 *
 * <p>3 列复合主键（user_id, character_id, rule_id）通过 {@code @IdClass(RelationshipMilestoneId.class)} 声明，
 * 天然保证同一用户/角色/规则组合只能触发一次（PK 唯一约束防重）。
 *
 * <p>表无额外 FK 约束，解耦 relationship 删除操作对里程碑历史的影响。
 */
@Data
@Entity
@Table(name = "relationship_milestones")
@IdClass(RelationshipMilestoneId.class)
public class RelationshipMilestoneEntity {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Id
    @Column(name = "character_id")
    private Long characterId;

    @Id
    @Column(name = "rule_id", length = 60)
    private String ruleId;

    @CreationTimestamp
    @Column(name = "triggered_at")
    private LocalDateTime triggeredAt;
}
