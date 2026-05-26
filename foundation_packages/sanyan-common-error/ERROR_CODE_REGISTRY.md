# ErrCode 区间登记表

> **本表是所有业务错误码（`ErrCode` 实现 enum）的人类可读索引。**
> 启动期 `ErrCodeConflictDetector` 自动扫描所有 enum，发现 code 重复直接启动失败——但**区间分配**靠人维护本表。
>
> **新模块申请 code 前必须先更新本表。**

---

## 区间分配总览

| 区间 | 模块 | 类名 | 位置 |
|---|---|---|---|
| **400-499** | 通用 | `CommonErrCode` | `foundation_packages/sanyan-common-error/` |
| **1000-1999** | user | `UserErrCode` | `business_packages/sanyan-user-core/src/main/java/com/sanyan/user/internal/` |
| **2000-2999** | chat | `ChatErrCode` | `business_packages/sanyan-chat-core/src/main/java/com/sanyan/chat/internal/` |
| **3000-3999** | character | `CharacterErrCode` | `business_packages/sanyan-character-core/src/main/java/com/sanyan/character/internal/` |
| **4000-4999** | llm | `LlmErrCode` | `business_packages/sanyan-llm-core/src/main/java/com/sanyan/llm/internal/` |
| **5000-5999** | memory | `MemoryErrCode` | `business_packages/sanyan-memory-core/src/main/java/com/sanyan/memory/internal/` |
| **6000-6999** | embedding | `EmbeddingErrCode` | `business_packages/sanyan-embedding-core/src/main/java/com/sanyan/embedding/internal/` |
| **7000-9999** | _（保留）_ | — | 留给未来新模块 |

---

## 详细 code 清单

### CommonErrCode（400-499）

| Code | 常量 | 文案 |
|---|---|---|
| 400 | `TOKEN_EXPIRED` | 登录态过期 |
| 401 | `TOKEN_INVALID` | 登录态无效 |
| 403 | `FORBIDDEN` | 无权限 |
| 404 | `NOT_FOUND` | 资源不存在 |
| 410 | `PARAM_INVALID` | 参数错误 |
| 500 | `INTERNAL_ERROR` | 服务器错误，请稍后重试 |

### UserErrCode（1000-1999）

| Code | 常量 | 文案 |
|---|---|---|
| 1001 | `PHONE_ALREADY_REGISTERED` | 手机号已注册 |
| 1002 | `USER_NOT_FOUND` | 用户不存在 |
| 1003 | `WRONG_PASSWORD` | 密码错误 |
| 1004 | `SMS_CODE_INVALID` | 验证码错误 |
| 1005 | `SMS_CODE_EXPIRED` | 验证码已过期 |
| 1006 | `SMS_SEND_TOO_FREQUENT` | 请稍后再试 |

### ChatErrCode（2000-2999）

| Code | 常量 | 文案 |
|---|---|---|
| 2001 | `MESSAGE_PROCESSING_FAILED` | 消息处理失败 |

### CharacterErrCode（3000-3999）

| Code | 常量 | 文案 |
|---|---|---|
| 3001 | `CHARACTER_NOT_FOUND` | 角色不存在 |
| 3002 | `RELATIONSHIP_NOT_FOUND` | 关系不存在 |
| 3003 | `INTIMACY_CONCURRENT_UPDATE` | 亲密度并发更新失败 |

### LlmErrCode（4000-4999）

| Code | 常量 | 文案 |
|---|---|---|
| 4001 | `LLM_CALL_FAILED` | AI 服务暂时不可用 |
| 4002 | `LLM_UPSTREAM_4XX` | AI 服务请求被拒绝 |
| 4003 | `LLM_UPSTREAM_UNAVAILABLE` | AI 服务暂时不可用 |
| 4005 | `LLM_PROVIDER_NOT_FOUND` | 找不到支持该任务类型的 LLM provider |
| 4006 | `LLM_PROVIDER_CONFLICT` | LLM provider 配置冲突：多个 provider 同时支持该 task type，请检查 application.yml |

> 注：4004 `EMBEDDING_SERVICE_UNAVAILABLE` 已于 S3 Phase 4 退役，迁到 `EmbeddingErrCode` 6001。

### MemoryErrCode（5000-5999）

| Code | 常量 | 文案 |
|---|---|---|
| 5001 | `PROFILE_REFRESH_CONFLICT` | Profile 刷新失败（乐观锁冲突） |
| 5002 | `EMBEDDING_SERVICE_UNAVAILABLE` | Embedding 服务不可用（业务层视角，调用方按此降级 RAG） |
| 5003 | `MEMORY_ITEM_NOT_FOUND` | 结构化记忆条目不存在 |

### EmbeddingErrCode（6000-6999）

| Code | 常量 | 文案 |
|---|---|---|
| 6001 | `EMBEDDING_SERVICE_UNAVAILABLE` | Embedding 服务不可用（硅基流动 API 4xx / 5xx 重试耗尽 / 网络异常） |

**关于 6001 与 5002 同名**：分层语义有意保留——6001 是 HTTP 客户端层（`SiliconFlowEmbeddingProvider`）抛的协议级失败，5002 是 Memory 业务层视角的不可用。`MemoryRagSearchService` 同时捕获两个 code 走相同的"返回空 list + 降级"逻辑。

---

## 新增 code 流程

1. **挑区间**：在"区间分配总览"里找你的模块所属段。如果是新模块，从"保留"段（7000+）挑一段
2. **改 enum**：在对应 `<Domain>ErrCode.java` 加新 entry
3. **更新本表**：把新 code + 常量名 + 文案加进上面的明细表
4. **mvn test**：`ErrCodeConflictDetectorTest` 会守护 code 唯一性
5. **commit**

---

## 历史变更

| 日期 | 变更 |
|---|---|
| 2026-05-15 | Plan 2 R3 final review 标记本表缺失为 S1 建议 |
| 2026-05-17 | 创建本表；同日 embedding 模块下线，6xxx 区间转为保留 |
| 2026-05-17 | S3 Phase 4：6xxx 启用 embedding 域；EmbeddingErrCode 新建 6001；LlmErrCode 删 4004 EMBEDDING_SERVICE_UNAVAILABLE（迁到 EmbeddingErrCode 6001） |
| 2026-05-17 | S3 Phase 7：sanyan-business 单体拆完，ErrCode 位置全部更新到新 -core 模块路径 |
| 2026-05-18 | Plan 3 A5：character 域新增 3002 RELATIONSHIP_NOT_FOUND / 3003 INTIMACY_CONCURRENT_UPDATE |
| 2026-05-25 | Final review I4：LLM 域新增 4006 LLM_PROVIDER_CONFLICT（多 provider 同 task type → fail-fast） |
| 2026-05-27 | Plan 4：memory 域新增 5003 MEMORY_ITEM_NOT_FOUND |
