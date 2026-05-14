package com.sanyan.memory.internal.rag;

import com.sanyan.memory.dto.MemoryFragment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * 向量切片 Repository。
 *
 * <p>本 Repository 提供两类方法：
 * <ul>
 *   <li>基础 CRUD + 按 (userId, characterId) 范围查询（P1 task 已有）：
 *       {@link #findByUserIdAndCharacterId} 用于离线索引重建 / 数据导出</li>
 *   <li><strong>ANN 相似度检索</strong>（P4 task 加）：
 *       {@link #findTopKByCosineSimilarity} 用 pgvector 的 cosine distance 操作符
 *       {@code <=>} + ivfflat 索引做 Top K 近邻召回</li>
 * </ul>
 *
 * <p>相似度查询走原生 SQL 而非 Spring Data 派生查询——因为相似度排序无法用 Spring Data
 * 方法名约定表达，必须显式写 {@code ORDER BY embedding <=> ?}。
 *
 * <p>pgvector 操作符语义提醒：
 * <ul>
 *   <li>{@code <=>}：cosine distance，值域 {@code [0, 2]}（0 = 完全相同，2 = 完全相反）</li>
 *   <li>cosine similarity = 1 - distance / 2，值域 {@code [-1, 1]}（1 = 完全相同）</li>
 *   <li>过滤 "相似度 &gt; 0.6" 等价于 "距离 &lt; 0.8"，但 SQL 里仍按距离排序更快</li>
 * </ul>
 */
public interface ChatEmbeddingRepository extends JpaRepository<ChatEmbeddingEntity, Long> {

    List<ChatEmbeddingEntity> findByUserIdAndCharacterId(Long userId, Long characterId);

    /**
     * 原生查询：按 cosine distance 排序取 Top K，并把距离换算成相似度返回。
     *
     * <p>选 native query 而非 JPQL 的两个原因：
     * <ul>
     *   <li>pgvector 操作符 {@code <=>} 不是 JPA 标准函数，JPQL 无法表达</li>
     *   <li>需要把 vector 字段强制转换 ({@code CAST(:queryVec AS vector)})，否则参数绑定为 text</li>
     * </ul>
     *
     * <p>返回 {@code List<Object[]>}（原始行）：列顺序 chunk_text(0), created_at(1), cos_sim(2)。
     * 调用方（{@link #findTopKByCosineSimilarity(Long, Long, float[], int, double)}）负责把
     * {@code Object[]} 转换成 {@link MemoryFragment}。
     *
     * <p>{@code queryVec} 参数用 {@code String} 而非 {@code float[]}：pgvector-java SDK 已
     * 经把向量序列化成 {@code "[v1,v2,...]"} 字面量，配合 SQL 里的 {@code CAST(...AS vector)}
     * 就能让 PG 直接当 vector 类型用，避免在 Repository 层挂自定义 Hibernate Type。
     */
    @Query(value = """
            SELECT chunk_text,
                   created_at,
                   1.0 - (embedding <=> CAST(:queryVec AS vector)) / 2.0 AS cos_sim
            FROM chat_embeddings
            WHERE user_id = :userId
              AND character_id = :characterId
              AND 1.0 - (embedding <=> CAST(:queryVec AS vector)) / 2.0 > :minSim
            ORDER BY embedding <=> CAST(:queryVec AS vector)
            LIMIT :topK
            """, nativeQuery = true)
    List<Object[]> findTopKRaw(
            @Param("userId") Long userId,
            @Param("characterId") Long characterId,
            @Param("queryVec") String queryVec,
            @Param("topK") int topK,
            @Param("minSim") double minSim);

    /**
     * 应用层包装：把 {@code float[]} 序列化成 pgvector 字面量后调 {@link #findTopKRaw}，
     * 再把 {@code Object[]} 行映射成 {@link MemoryFragment}。
     *
     * <p>用 default method 而非单独抽 RagSearchHelper 类：实现只是字符串格式化 + 列映射，
     * 逻辑很轻；接口内 default 让调用方一行调用拿到 typed result，省一层间接性。
     */
    default List<MemoryFragment> findTopKByCosineSimilarity(
            Long userId, Long characterId, float[] queryVec, int topK, double minSim) {
        String vecLiteral = floatArrayToPgVectorString(queryVec);
        List<Object[]> rows = findTopKRaw(userId, characterId, vecLiteral, topK, minSim);
        return rows.stream()
                .map(r -> new MemoryFragment(
                        (String) r[0],
                        ((Timestamp) r[1]).toInstant(),
                        ((Number) r[2]).doubleValue()))
                .toList();
    }

    /**
     * 把 float[] 序列化成 pgvector 字面量 {@code "[v1,v2,...]"}。
     *
     * <p>与 {@link PgVectorType#nullSafeSet} 走的是不同路径：那条路径是 Entity 写入时
     * 用 PGobject 让 JDBC 驱动序列化；这里是 SQL 参数绑定，直接用字符串字面量 +
     * {@code CAST(... AS vector)} 让 PG 自己解析。两种方式 PG 端等价。
     */
    private static String floatArrayToPgVectorString(float[] vec) {
        StringBuilder sb = new StringBuilder(vec.length * 8 + 2);
        sb.append('[');
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vec[i]);
        }
        sb.append(']');
        return sb.toString();
    }
}
