package com.sanyan.memory.internal.profile;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * {@link MemoryProfileEntity} 的复合主键 (user_id, character_id)。
 *
 * <p>JPA 要求 {@code @IdClass} 引用的复合 PK 类必须：
 * <ul>
 *   <li>实现 {@link Serializable}</li>
 *   <li>有无参构造（Lombok {@code @NoArgsConstructor} 生成）</li>
 *   <li>正确实现 {@code equals} / {@code hashCode}（Lombok {@code @Data} 生成）</li>
 *   <li>字段名 / 类型与 Entity 上加 {@code @Id} 的字段严格一致</li>
 * </ul>
 *
 * <p>选 {@code @IdClass} 而非 {@code @EmbeddedId}：Entity 字段直接是 {@code userId / characterId}（不嵌一层 id 对象），
 * 调用方写 {@code entity.getUserId()} 而不是 {@code entity.getId().getUserId()}，更贴近 N1 task
 * MemorySummaryEntity 的扁平访问风格。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MemoryProfileId implements Serializable {

    private Long userId;
    private Long characterId;
}
