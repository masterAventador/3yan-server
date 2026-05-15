package com.sanyan;

import com.sanyan.chat.event.MessagePersistedEvent;
import com.sanyan.chat.internal.MessageEntity;
import com.sanyan.chat.internal.MessageRepository;
import com.sanyan.chat.internal.SenderType;
import com.sanyan.common.test.PostgresTestcontainerSupport;
import com.sanyan.llm.internal.EmbeddingProvider;
import com.sanyan.llm.internal.LLMProviderRouter;
import com.sanyan.llm.internal.LLMTaskType;
import com.sanyan.memory.MemoryApi;
import com.sanyan.memory.MemoryConstants;
import com.sanyan.memory.dto.MemoryContext;
import com.sanyan.memory.internal.profile.MemoryProfileEntity;
import com.sanyan.memory.internal.profile.MemoryProfileRepository;
import com.sanyan.memory.internal.rag.ChatEmbeddingEntity;
import com.sanyan.memory.internal.rag.ChatEmbeddingRepository;
import com.sanyan.memory.internal.rag.RagIndexWorker;
import com.sanyan.memory.internal.summary.MemorySummaryEntity;
import com.sanyan.memory.internal.summary.MemorySummaryRepository;
import com.sanyan.memory.internal.summary.SummaryScheduler;
import com.sanyan.memory.event.UserMessageProfileRefreshListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * Plan 2 Task R1：端到端集成测试，覆盖三条核心长期记忆链路。
 *
 * <p>本 IT 启动完整 Spring 上下文（{@code SanyanApplication}），用 Testcontainers PG (with
 * pgvector) + Flyway V1→V7 拿到真实 schema，mock 掉外部依赖（LLM / Embedding / Redis），
 * 直接调用 Scheduler/Listener 同步方法（绕过 {@code @Async} + {@code @TransactionalEventListener}
 * 异步路径，避免测试需要等待异步完成），验证三条链路串通：
 *
 * <ol>
 *   <li><b>Summary 30 条触发</b>：插 30 条 user/ai 交替消息 → 调
 *       {@link SummaryScheduler#onMessagePersisted} → 验证 {@code memory_summaries} 表落库一条</li>
 *   <li><b>Profile 刷新链路</b>：插一条 "我家有只猫，名字叫橘子" → 调
 *       {@link UserMessageProfileRefreshListener#onMessagePersisted} →
 *       验证 {@code memory_profiles.summary_text} 含 LLM 输出的自然语言画像</li>
 *   <li><b>RAG 检索召回</b>：插若干 {@link ChatEmbeddingEntity}（含 fixture vector +
 *       chunk_text，其中一条 chunk_text 含"橘子"）→ 用 mock embedding provider 让 query
 *       向量与"橘子" chunk 高度相似 → 调 {@link MemoryApi#getRelevantContext} →
 *       验证返回的 {@link MemoryContext#text()} 含"橘子"</li>
 * </ol>
 *
 * <h2>为什么用 mock 而不是真实外部服务</h2>
 * <ul>
 *   <li><b>LLM</b>（豆包 / DeepSeek）：需要 API key，CI 跑测试不能依赖云服务可用</li>
 *   <li><b>Embedding</b>（远程 BGE-M3 服务）：同上，且部署在另一台机器</li>
 *   <li><b>Redis</b>：避免本地 Redis 依赖；用 {@code @MockBean} 替换 {@code StringRedisTemplate} +
 *       配套 mock {@code ValueOperations}（参考 {@code SanyanApplicationTest} 已有模式）</li>
 * </ul>
 *
 * <h2>为什么直接调 Scheduler/Listener 方法而不是发事件</h2>
 * <p>真实路径走 {@code @TransactionalEventListener(AFTER_COMMIT)} + {@code @Async}：
 * <ul>
 *   <li>{@code AFTER_COMMIT}：要求事件发布在事务内，提交后才触发——IT 里事务管理复杂，
 *       靠 {@code TestTransaction.flagForCommit} 或 propagation 拆分容易出错</li>
 *   <li>{@code @Async}：需要 {@code Awaitility} 轮询等待异步完成，引入额外依赖 + 增加不稳定性</li>
 * </ul>
 * 直接调被注解的 public 方法 == 同步执行 listener 逻辑，验证的是"业务逻辑串通"这个核心目标。
 * 事件订阅/异步本身已经在各 listener 单测里覆盖。
 *
 * <h2>测试隔离</h2>
 * <p>每个 {@code @Test} 用唯一 userId（{@code USER_ID_SUMMARY} / {@code USER_ID_PROFILE} /
 * {@code USER_ID_RAG}）避免互相污染。Flyway clean+migrate 在 {@link PostgresTestcontainerSupport}
 * 的其他 IT 里走每个用例隔离，本 IT 用 userId 维度隔离更轻量（30 条消息 × 多用例 ≪ Flyway clean
 * 的开销）。
 *
 * <h2>不验证的内容</h2>
 * <ul>
 *   <li><b>HTTP 层</b>：没起 web server（{@code webEnvironment = NONE}）。HTTP → MessageService
 *       → 事件发布的链路有 Controller / @WebMvcTest 单独覆盖，本 IT 关注事件 → 长期记忆链路</li>
 *   <li><b>真实 @Async / @TransactionalEventListener 时机</b>：已在各 listener 单测里覆盖</li>
 *   <li><b>真实 LLM 生成质量</b>：本 IT 是基础设施串通验证，不评估 LLM 输出质量</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@AutoConfigureTestDatabase(replace = NONE)
class Plan2EndToEndIT extends PostgresTestcontainerSupport {

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry registry) {
        // 用 Testcontainers PG（pgvector 镜像）替代本地 PG 数据源
        registry.add("spring.datasource.url", PostgresTestcontainerSupport::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestcontainerSupport::username);
        registry.add("spring.datasource.password", PostgresTestcontainerSupport::password);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        // Flyway V1→V7 跑出完整 schema（包括 pgvector 扩展 + chat_embeddings 表）
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("spring.flyway.clean-disabled", () -> "false");
        // Hibernate 完全交给 Flyway 建表
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        // 关掉 Redis autoconfigure（已 mock StringRedisTemplate，不需要真连接）
        registry.add("spring.autoconfigure.exclude", () ->
                "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration");
        // JWT 等其它 sanyan.* 必填占位（与 src/test/resources/application.yml 对齐）
        registry.add("sanyan.jwt.secret", () ->
                "test-secret-key-at-least-256-bits-long-for-hmac-sha-testing");
        registry.add("sanyan.jwt.expiration-days", () -> "1");
        registry.add("sanyan.doubao.api-key", () -> "test");
        registry.add("sanyan.doubao.model", () -> "test");
        registry.add("sanyan.doubao.base-url", () -> "http://localhost:9999");
        registry.add("sanyan.cos.secret-id", () -> "test");
        registry.add("sanyan.cos.secret-key", () -> "test");
        registry.add("sanyan.cos.bucket", () -> "test-bucket");
        registry.add("sanyan.cos.region", () -> "ap-guangzhou");
        registry.add("sanyan.tts.enabled", () -> "false");
        registry.add("sanyan.tts.app-id", () -> "test");
        registry.add("sanyan.tts.access-token", () -> "test");
    }

    /** Summary 链路用 userId，避免与其他用例的消息混淆。 */
    private static final Long USER_ID_SUMMARY = 1000L;
    /** Profile 链路用 userId。 */
    private static final Long USER_ID_PROFILE = 2000L;
    /** RAG 链路用 userId。 */
    private static final Long USER_ID_RAG = 3000L;
    /** MVP 单角色硬编码 = 1L（与 MessageService.DEFAULT_CHARACTER_ID 对齐）。 */
    private static final Long CHARACTER_ID = 1L;

    @MockBean
    private LLMProviderRouter llmRouter;

    @MockBean
    private EmbeddingProvider embeddingProvider;

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    /**
     * RagIndexWorker 在 SanyanApplication 启动后立即被 {@code @Scheduled(fixedDelay=10000)} 调度，
     * 在 {@code @BeforeEach} 配置 stream ops 之前就会触发一次 poll 并因 mock 未 stub 抛 NPE
     * （ERROR 日志噪声）。本 IT 不验证 RAG 索引落库链路（那是 P5 listener+worker 单测的范围），
     * 直接 {@code @MockBean} 替换掉，禁用其调度任务，让日志干净。
     */
    @MockBean
    private RagIndexWorker ragIndexWorker;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private MemorySummaryRepository summaryRepository;

    @Autowired
    private MemoryProfileRepository profileRepository;

    @Autowired
    private ChatEmbeddingRepository embeddingRepository;

    @Autowired
    private SummaryScheduler summaryScheduler;

    @Autowired
    private UserMessageProfileRefreshListener profileRefreshListener;

    @Autowired
    private MemoryApi memoryApi;

    @BeforeEach
    void setupRedisMockChain() {
        // KvCache.setIfAbsent 走 redis.opsForValue().setIfAbsent(key, value, ttl)
        // 默认返回 true（"获取节流锁成功"），让 profile listener 不被节流跳过
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(Boolean.TRUE);
    }

    /**
     * 链路 1：30 条消息累积触发 summary 生成。
     *
     * <p>流程：插 30 条 user/ai 交替消息 → 直接调
     * {@link SummaryScheduler#onMessagePersisted} → 验证 {@code memory_summaries} 表里
     * 落库一条，period 区间覆盖 [first, last] message id，message_count = 30。
     */
    @Test
    @Transactional
    void summary_triggersAfter30Messages() {
        // mock LLM 返回固定摘要文本
        when(llmRouter.chat(eq(LLMTaskType.BACKGROUND), anyList()))
                .thenReturn("用户和小婉聊了 30 条关于日常生活的对话纪要");

        // 插 30 条消息（user / ai 交替，模拟真实对话）
        List<MessageEntity> saved = new ArrayList<>(30);
        for (int i = 0; i < 30; i++) {
            MessageEntity m = new MessageEntity();
            m.setUserId(USER_ID_SUMMARY);
            m.setSenderType(i % 2 == 0 ? SenderType.USER : SenderType.AI);
            m.setContent("第" + i + "条消息");
            messageRepository.save(m);
            saved.add(m);
        }
        Long firstId = saved.get(0).getId();
        Long lastId = saved.get(saved.size() - 1).getId();

        // 直接调 scheduler 同步方法。SummaryScheduler 用 @Async 注解，注入的 Spring bean 是
        // CGLIB 代理，直接调会被异步化导致测试断言时 scheduler 还没跑完；用 AopTestUtils 拿到
        // 未代理的 target 实例，避开 @Async / @TransactionalEventListener 走同步路径。
        SummaryScheduler rawScheduler = AopTestUtils.getTargetObject(summaryScheduler);
        rawScheduler.onMessagePersisted(new MessagePersistedEvent(
                lastId, USER_ID_SUMMARY, CHARACTER_ID, SenderType.AI));

        // 验证 memory_summaries 表落库一条
        List<MemorySummaryEntity> summaries = summaryRepository.findAll().stream()
                .filter(s -> USER_ID_SUMMARY.equals(s.getUserId()))
                .toList();
        assertThat(summaries)
                .as("30 条消息达到 SUMMARY_TRIGGER_THRESHOLD 应触发一条摘要落库")
                .hasSize(1);
        MemorySummaryEntity summary = summaries.get(0);
        assertThat(summary.getCharacterId()).isEqualTo(CHARACTER_ID);
        assertThat(summary.getMessageCount()).isEqualTo(30);
        assertThat(summary.getPeriodStartMessageId()).isEqualTo(firstId);
        assertThat(summary.getPeriodEndMessageId()).isEqualTo(lastId);
        assertThat(summary.getSummaryText()).contains("对话纪要");
    }

    /**
     * 链路 2：用户消息触发 profile 刷新（Plan 2.5 free-form summary）。
     *
     * <p>流程：插一条 user 消息 "我家有只猫，名字叫橘子" → mock LLM 返回自然语言画像 →
     * 直接调 {@link UserMessageProfileRefreshListener#onMessagePersisted} → 验证
     * {@code memory_profiles.summary_text} 是 LLM 输出的画像段落。
     */
    @Test
    @Transactional
    void profile_refreshesFromUserMessage() {
        String llmSummary = "用户家里养了一只名叫橘子的猫，喜欢分享日常宠物趣事。";
        when(llmRouter.chat(eq(LLMTaskType.BACKGROUND), anyList())).thenReturn(llmSummary);

        // 插入一条 user 消息
        MessageEntity userMsg = new MessageEntity();
        userMsg.setUserId(USER_ID_PROFILE);
        userMsg.setSenderType(SenderType.USER);
        userMsg.setContent("我家有只猫，名字叫橘子");
        messageRepository.save(userMsg);

        // 直接调 listener 同步方法（绕过 @Async + @TransactionalEventListener）
        UserMessageProfileRefreshListener rawListener =
                AopTestUtils.getTargetObject(profileRefreshListener);
        rawListener.onMessagePersisted(new MessagePersistedEvent(
                userMsg.getId(), USER_ID_PROFILE, CHARACTER_ID, SenderType.USER));

        // 验证 memory_profiles 表新增/更新一条，summary_text 是 LLM 输出
        MemoryProfileEntity profile = profileRepository
                .findByUserIdAndCharacterId(USER_ID_PROFILE, CHARACTER_ID)
                .orElseThrow(() -> new AssertionError(
                        "profile 链路应当为 user=" + USER_ID_PROFILE + " 落库一条 profile"));

        assertThat(profile.getSummaryText())
                .as("summary_text 应该是 LLM 输出的自然语言画像")
                .isEqualTo(llmSummary);
    }

    /**
     * 链路 3：RAG 检索召回相关历史片段。
     *
     * <p>流程：插若干 {@link ChatEmbeddingEntity}（其中一条 chunk_text 含"橘子"，向量
     * 与 query 向量高度相似；其余两条向量正交于 query）→ mock embeddingProvider 返回
     * 与"橘子" chunk 同方向的 query 向量 → 调 {@link MemoryApi#getRelevantContext} →
     * 验证返回的 {@link MemoryContext#text()} 含"橘子"片段。
     */
    @Test
    @Transactional
    void rag_returnsRelevantFragments() {
        // 准备 3 条 chunks：
        //  - 高相似（橘子，向量 [1, 0, 0, ...]）
        //  - 中相似（养猫一般话题，向量 [0.9, 0.1, 0, ...]）
        //  - 低相似（旅行，向量 [0, 1, 0, ...]，cos sim = 0 应被 0.6 阈值过滤）
        insertEmbedding(USER_ID_RAG, "我和用户聊到他家的猫橘子，最近瘦了一些", oneHotVec(0));
        insertEmbedding(USER_ID_RAG, "他养猫养了好几年，对猫很有经验", scaledVec(0, 0.9f, 1, 0.1f));
        insertEmbedding(USER_ID_RAG, "他喜欢去云南旅行", oneHotVec(1));
        embeddingRepository.flush();

        // mock embedding provider 返回与 chunk[0] 同方向的查询向量 [1, 0, 0, ...]
        when(embeddingProvider.embed(anyList()))
                .thenReturn(List.of(oneHotVec(0)));

        // 调 MemoryApi 拿整合 context
        MemoryContext ctx = memoryApi.getRelevantContext(
                USER_ID_RAG, CHARACTER_ID, "上次那只猫怎么样");

        assertThat(ctx).as("应当至少召回 RAG 片段，context 非空").isNotNull();
        assertThat(ctx.text())
                .as("context 应含'橘子'相关 chunk_text")
                .contains("橘子");
        // 低相似度的"旅行" chunk 被 RAG_MIN_COS_SIM(0.6) 阈值过滤掉
        assertThat(ctx.text())
                .as("低相似度的旅行片段应被阈值过滤")
                .doesNotContain("旅行");
    }

    // ============ helpers ============

    private void insertEmbedding(Long userId, String chunkText, float[] embedding) {
        ChatEmbeddingEntity e = new ChatEmbeddingEntity();
        e.setUserId(userId);
        e.setCharacterId(CHARACTER_ID);
        e.setMessageIds(new Long[]{1L, 2L, 3L, 4L, 5L});
        e.setChunkText(chunkText);
        e.setEmbedding(embedding);
        e.setTokenCount(50);
        embeddingRepository.save(e);
    }

    /**
     * 构造 {@link MemoryConstants#EMBEDDING_DIM}（=1024）维 one-hot 向量：仅 {@code idx} 位为 1。
     * <p>用于让两条 chunks 的相似度差异在单一维度上可控（参考 {@code MemoryRagSearchServiceIT} 的
     * vec2d 模式）。
     */
    private static float[] oneHotVec(int idx) {
        float[] v = new float[MemoryConstants.EMBEDDING_DIM];
        v[idx] = 1f;
        return v;
    }

    /** 构造两维都非零的向量（其余补 0）。 */
    private static float[] scaledVec(int idx1, float v1, int idx2, float v2) {
        float[] v = new float[MemoryConstants.EMBEDDING_DIM];
        v[idx1] = v1;
        v[idx2] = v2;
        return v;
    }
}
