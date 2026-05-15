package com.sanyan.memory.internal.profile;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 结构化档案 Repository。
 *
 * <p>主键查询：复合 PK {@link MemoryProfileId} 直接走 {@link #findById(Object)}。
 *
 * <p>{@link #findByUserIdAndCharacterId} 派生查询：方法签名更直观（不用先 new 一个
 * MemoryProfileId 包装两个 Long），是 {@code MemoryProfileRefreshService}「读 → 刷新 → 写回」
 * 链路的入口。{@link #findById} 也保留，给框架级 / 习惯用 PK 对象的场景用。
 *
 * <p>"upsert"（首次抽取时该 user × character 还没记录）由调用方处理：先
 * findByUserIdAndCharacterId → 若空则 new + save，若有则 merge + save。本 Repository 不
 * 提供专用 upsert 方法，避免误用绕过乐观锁。
 */
public interface MemoryProfileRepository
        extends JpaRepository<MemoryProfileEntity, MemoryProfileId> {

    Optional<MemoryProfileEntity> findByUserIdAndCharacterId(Long userId, Long characterId);
}
