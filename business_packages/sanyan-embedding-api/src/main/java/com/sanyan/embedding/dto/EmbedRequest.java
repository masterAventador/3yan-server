package com.sanyan.embedding.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 远程 embedding 服务的请求体。
 *
 * <p>{@code texts} 为待向量化的文本列表。校验规则：
 * <ul>
 *   <li>列表本身 {@link NotEmpty}（null 与空 list 都拒绝）</li>
 *   <li>每条 text 长度 ≤ 2000（BGE-M3 默认 token 上限对应字符量级，超长应在调用方先切片）</li>
 * </ul>
 *
 * <p>注意：元素级 Bean Validation 注解 {@code List<@Size(max = 2000) String>} 依赖
 * Jakarta Validation 3.0+（Hibernate Validator 8+）。
 */
public record EmbedRequest(
        @NotEmpty List<@Size(max = 2000) String> texts) {}
