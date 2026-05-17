package com.sanyan.memory.internal.rag;

import com.pgvector.PGvector;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.usertype.UserType;

import java.io.Serializable;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Arrays;

/**
 * Hibernate 6 UserType：把 Java {@code float[]} 与 PostgreSQL {@code vector(N)} 列双向映射。
 *
 * <p>用途：{@link ChatEmbeddingEntity#embedding} 字段持有 BGE-M3 输出的 1024 维浮点向量，
 * 需写入 V7 schema 的 {@code embedding vector(1024)} 列。Hibernate 不原生支持
 * pgvector 类型，必须通过自定义 {@link UserType} 衔接：
 * <ul>
 *   <li>写：用 pgvector-java SDK 的 {@link PGvector} 包装 float[]，
 *       走 {@link PreparedStatement#setObject(int, Object)}（type code {@code Types.OTHER}），
 *       JDBC 驱动调用 {@code PGvector.toString()} 序列化成 {@code '[v1,v2,...]'} 字面量</li>
 *   <li>读：{@link ResultSet#getObject(int)} 拿到的是 {@link PGvector} 或回退字符串，
 *       统一构造 {@code PGvector} 后取 {@code toArray()}</li>
 * </ul>
 *
 * <p>注：用 Hibernate 6 的 {@code UserType<float[]>} 泛型接口（不是 ORM 5.x 的老 UserType）。
 * 工程已经基于 Spring Boot 3.2.5 + Hibernate 6.4.4。
 *
 * <p>挂载方式：在 {@link ChatEmbeddingEntity} 的 embedding 字段上加
 * {@code @org.hibernate.annotations.Type(PgVectorType.class)}。
 */
public class PgVectorType implements UserType<float[]> {

    @Override
    public int getSqlType() {
        // pgvector 不是标准 JDBC 类型，用 OTHER 让驱动按 setObject 默认走 PGobject 序列化
        return Types.OTHER;
    }

    @Override
    public Class<float[]> returnedClass() {
        return float[].class;
    }

    @Override
    public boolean equals(float[] x, float[] y) {
        return Arrays.equals(x, y);
    }

    @Override
    public int hashCode(float[] x) {
        return Arrays.hashCode(x);
    }

    @Override
    public float[] nullSafeGet(
            ResultSet rs,
            int position,
            SharedSessionContractImplementor session,
            Object owner) throws SQLException {
        Object raw = rs.getObject(position);
        if (raw == null) {
            return null;
        }
        if (raw instanceof PGvector pgv) {
            return pgv.toArray();
        }
        // fallback：JDBC 驱动版本若未自动反序列化（如未注册 vector 类型），
        // 返回的是 "[v1,v2,...]" 字符串，用 PGvector 构造器解析
        return new PGvector(raw.toString()).toArray();
    }

    @Override
    public void nullSafeSet(
            PreparedStatement st,
            float[] value,
            int index,
            SharedSessionContractImplementor session) throws SQLException {
        if (value == null) {
            st.setNull(index, Types.OTHER);
        } else {
            st.setObject(index, new PGvector(value));
        }
    }

    @Override
    public float[] deepCopy(float[] value) {
        return value == null ? null : value.clone();
    }

    @Override
    public boolean isMutable() {
        return true;
    }

    @Override
    public Serializable disassemble(float[] value) {
        return value == null ? null : value.clone();
    }

    @Override
    public float[] assemble(Serializable cached, Object owner) {
        return cached == null ? null : ((float[]) cached).clone();
    }
}
