package com.sanyan.character.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * RelationshipEntity 的 JPA Repository。
 *
 * <p>使用复合主键 {@link RelationshipId} 作为 ID 类型。
 * {@link #findByUserIdAndCharacterId} 用于按用户 + 角色查询单条记录，
 * 是业务层"找或建"场景的核心入口。
 */
public interface RelationshipRepository extends JpaRepository<RelationshipEntity, RelationshipId> {

    Optional<RelationshipEntity> findByUserIdAndCharacterId(Long userId, Long characterId);

    /** 列出与指定角色已建立关系的所有 userId（本期单角色，proactive 触发器遍历用户用）。 */
    @Query("SELECT r.userId FROM RelationshipEntity r WHERE r.characterId = :characterId")
    List<Long> findUserIdsByCharacterId(Long characterId);
}
