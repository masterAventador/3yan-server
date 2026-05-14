package com.sanyan.memory.internal.rag;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Type;

import java.time.Instant;

/**
 * 向量切片（V7 chat_embeddings 表）：
 *
 * <p>长期记忆「向量检索（RAG）」通道：每 N 条消息切成一段 chunk_text，用 BGE-M3 生成
 * 1024 维 embedding 入库；查询时按当前消息 embedding 做 cosine 相似度 ANN 召回，
 * top-K 历史片段拼进 LLM 上下文。schema 由 V7__init_chat_embeddings.sql 维护，本
 * Entity 字段映射须与该 migration 严格对齐——V7MigrationIT（bootstrap 模块）守护字段
 * 类型 / nullability / 索引；本 Entity 同步的字段定义在此。
 *
 * <p>{@code message_ids} 用 PG 原生 {@code BIGINT[]} 数组而非关联表：chunk → message 是
 * 固定 N:M 但 N 小（M2 切片每段 ~10 条），数组比 join 表更轻量；查询走 chunk 维度，
 * 反查 message 罕见。Hibernate 把 Java {@code Long[]} 直接映射到 PG int8[]。
 *
 * <p>{@code embedding} 用自定义 {@link PgVectorType} 把 Java {@code float[]} 与 PG
 * {@code vector(1024)} 双向序列化（依赖 com.pgvector:pgvector SDK）。Hibernate 不
 * 原生支持 pgvector 类型，必须通过 UserType 衔接。
 *
 * <p>{@code @PrePersist} 兜底：应用层未显式赋值 {@code createdAt} 时用 {@link Instant#now()}
 * 填充，等价于 V7 schema {@code DEFAULT NOW()}。两层保护：DB 默认值 + Entity 钩子，
 * 避免任一层失效导致 NOT NULL 违反。
 */
@Entity
@Table(name = "chat_embeddings")
@Data
@NoArgsConstructor
public class ChatEmbeddingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "character_id", nullable = false)
    private Long characterId;

    /**
     * 本切片覆盖的消息 ID 集合，按 PG BIGINT[] 数组存。
     */
    @Column(name = "message_ids", nullable = false, columnDefinition = "BIGINT[]")
    private Long[] messageIds;

    @Column(name = "chunk_text", nullable = false, columnDefinition = "TEXT")
    private String chunkText;

    /**
     * BGE-M3 1024 维向量。通过 {@link PgVectorType} 走 PGvector 序列化到 PG vector(1024)。
     */
    @Type(PgVectorType.class)
    @Column(name = "embedding", nullable = false, columnDefinition = "vector(1024)")
    private float[] embedding;

    /**
     * 本切片在 chunk_text 上的 token 数（可空：早期切片或非 LLM 统计场景允许缺失）。
     */
    @Column(name = "token_count")
    private Integer tokenCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
