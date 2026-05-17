package com.sanyan.character.internal.plotrule;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * RelationshipMilestoneEntity 3 列复合主键 id 类。
 *
 * <p>JPA @IdClass 要求：
 * <ul>
 *   <li>实现 Serializable</li>
 *   <li>有无参构造（@NoArgsConstructor）</li>
 *   <li>equals / hashCode 基于全部 PK 字段（@Data 自动生成）</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RelationshipMilestoneId implements Serializable {
    private Long userId;
    private Long characterId;
    private String ruleId;
}
