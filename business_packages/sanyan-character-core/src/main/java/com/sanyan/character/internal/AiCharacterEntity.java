package com.sanyan.character.internal;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "ai_character")
public class AiCharacterEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 50)
    private String name;
    private String avatar;
    @CreationTimestamp
    private LocalDateTime createdAt;

    /** 角色基础 system prompt，对应 V2 migration 的 base_prompt TEXT NOT NULL DEFAULT ''。 */
    @Column(name = "base_prompt", nullable = false, columnDefinition = "TEXT")
    private String basePrompt = "";

    /**
     * 角色阶段覆盖配置（JSON 对象），对应 V2 migration 的 persona_config JSONB NOT NULL DEFAULT '{}'。
     * {@code @JdbcTypeCode(SqlTypes.JSON)} 告知 Hibernate 6 把该字段序列化到 PG jsonb 列。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "persona_config", nullable = false, columnDefinition = "jsonb")
    private String personaConfig = "{}";
}
