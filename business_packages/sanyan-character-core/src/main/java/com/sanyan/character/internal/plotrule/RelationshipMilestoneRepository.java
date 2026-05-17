package com.sanyan.character.internal.plotrule;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * RelationshipMilestoneEntity 的 Spring Data JPA Repository。
 *
 * <p>核心用途：通过 {@code existsById(RelationshipMilestoneId)} 查询某个里程碑是否已触发，
 * 配合 3 列复合 PK 实现"同一规则对同一用户/角色只触发一次"的防重保障。
 */
public interface RelationshipMilestoneRepository
        extends JpaRepository<RelationshipMilestoneEntity, RelationshipMilestoneId> {
}
