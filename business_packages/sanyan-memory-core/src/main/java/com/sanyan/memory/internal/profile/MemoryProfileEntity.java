package com.sanyan.memory.internal.profile;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/**
 * 结构化档案（V6 memory_profiles 表）：
 *
 * <p>长期记忆「结构化档案」通道：以 (user_id, character_id) 为复合主键，存储用户在该
 * 角色视角下被「记住」的结构化字段（basic_info / preferences / events / emotion_line），
 * 合并写时走「读 → JSON merge → version + 1 乐观锁更新」流程（O3 task 实现），避免并发覆盖。
 *
 * <p>schema 由 V6__init_memory_profiles.sql 维护，本 Entity 字段映射须与该 migration 严格对齐——
 * V6MigrationIT（bootstrap 模块）守护字段类型 / nullability / 复合 PK；本 Entity 同步的字段定义在此。
 *
 * <p>JSONB 映射：Spring Boot 3.2.5 自带 Hibernate 6.4.4，内置 {@code @JdbcTypeCode(SqlTypes.JSON)}
 * 把 Java {@code Map<String, Object>} 与 PG {@code jsonb} 双向序列化（底层走 Jackson）。
 * 不引入 hypersistence-utils 第三方依赖。
 *
 * <p>{@code @Version Integer version}：JPA 乐观锁标准用法。每次 UPDATE 时 Hibernate 自动
 * 检查 version 当前值 + 加 1；并发更新冲突时抛 {@link org.springframework.orm.ObjectOptimisticLockingFailureException}，
 * 由 O3 MemoryProfileMergeService 捕获并重试一次。
 *
 * <p>{@code @PrePersist} + {@code @PreUpdate} 兜底：应用层未显式赋值 {@code updatedAt} 时用
 * {@link Instant#now()} 填充，等价于 V6 schema 的 {@code DEFAULT NOW()}。两层保护：DB 默认值 +
 * Entity 钩子，避免任一层失效导致 NOT NULL 违反。
 */
@Entity
@Table(name = "memory_profiles")
@IdClass(MemoryProfileId.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MemoryProfileEntity {

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Id
    @Column(name = "character_id", nullable = false)
    private Long characterId;

    /**
     * JSONB 结构化档案，schema 见 V6__init_memory_profiles.sql 头注释：
     * basic_info / preferences / events / emotion_line。
     *
     * <p>用 {@code Map<String, Object>} 而非具体 record 类型：profile 字段结构演进频繁
     * （events / emotion_line 由 LLM 抽取，schema 弱约束），用 Map 避免每次新增字段都改 Entity；
     * 类型校验交给 O2 ExtractService + O3 MergeService 的业务逻辑层做。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "profile_jsonb", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> profileJsonb;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        if (updatedAt == null) {
            updatedAt = Instant.now();
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
