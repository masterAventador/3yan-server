package com.sanyan.memory.internal.rag;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 向量切片 Repository。
 *
 * <p>本 Repository 只提供基础 CRUD + 按 (userId, characterId) 范围查询：
 * <ul>
 *   <li>{@link #findByUserIdAndCharacterId}：取某用户 × 某角色的所有切片，用于
 *       离线索引重建 / 数据导出场景</li>
 * </ul>
 *
 * <p>核心的 ANN 相似度检索（P4 task 的 {@code MemoryRagSearchService}）会用
 * 原生 SQL（{@code embedding <=> ?} 操作符 + ivfflat 索引）实现，不在本 Repository 上
 * 用 Spring Data 方法名约定——因为相似度排序无法用 Spring Data 派生查询表达。
 */
public interface ChatEmbeddingRepository extends JpaRepository<ChatEmbeddingEntity, Long> {

    List<ChatEmbeddingEntity> findByUserIdAndCharacterId(Long userId, Long characterId);
}
