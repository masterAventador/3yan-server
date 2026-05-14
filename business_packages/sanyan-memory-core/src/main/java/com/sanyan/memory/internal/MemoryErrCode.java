package com.sanyan.memory.internal;

import com.sanyan.common.error.ErrCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Memory 模块错误码 enum（Plan 2 引入）。
 *
 * <p>code 区间 <b>5000-5999</b>，登记在 {@code sanyan-common-error/ERROR_CODE_REGISTRY.md}。
 * 启动时由 {@code ErrCodeConflictDetector} 扫描；同 code 重复定义会让 Spring 上下文启动失败。
 *
 * <p>项目通过抛出 {@code BusinessException(MemoryErrCode.XXX)} 让 {@code GlobalExceptionHandler}
 * 统一序列化为 {@code BaseResp.failed(code, message)} 给端上识别。
 *
 * <ul>
 *   <li>{@link #PROFILE_MERGE_CONFLICT}：O3 task 用——结构化档案乐观锁冲突，重试 1 次仍失败时抛出。
 *       由调用方（O4 listener）决定重新入队或丢弃；不在 service 层再吞掉。</li>
 *   <li>{@link #EMBEDDING_SERVICE_UNAVAILABLE}：P3/P4 task 用——远程 BGE-M3 embedding server
 *       连接失败 / 5xx / 重试耗尽时抛出；RAG 检索层捕获后<b>优雅降级</b>返回空列表（spec §4.1）。</li>
 * </ul>
 */
@Getter
@AllArgsConstructor
public enum MemoryErrCode implements ErrCode {

    PROFILE_MERGE_CONFLICT(5001, "Profile 合并失败（乐观锁冲突）"),
    EMBEDDING_SERVICE_UNAVAILABLE(5002, "Embedding 服务不可用"),
    ;

    private final int code;
    private final String defaultMessage;
}
