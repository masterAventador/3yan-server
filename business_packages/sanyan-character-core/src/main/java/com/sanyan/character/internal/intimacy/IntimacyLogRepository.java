package com.sanyan.character.internal.intimacy;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * IntimacyLogEntity 的 JPA Repository。
 *
 * <p>派生查询 {@link #findTop10ByUserIdAndCharacterIdOrderByCreatedAtDesc} 利用
 * V4 migration 创建的 idx_intimacy_logs_user_char_time 索引（user_id, character_id, created_at DESC），
 * 高效返回指定用户 + 角色的最新 10 条亲密度变更记录。
 */
public interface IntimacyLogRepository extends JpaRepository<IntimacyLogEntity, Long> {

    List<IntimacyLogEntity> findTop10ByUserIdAndCharacterIdOrderByCreatedAtDesc(
            Long userId, Long characterId);
}
