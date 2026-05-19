package com.sanyan.bootstrap;

import com.sanyan.character.event.MessagePersistedListener;
import com.sanyan.character.internal.RelationshipFetchService;
import com.sanyan.character.internal.RelationshipRepository;
import com.sanyan.character.internal.intimacy.IntimacyLogRepository;
import com.sanyan.chat.ChatApi;
import com.sanyan.chat.SenderType;
import com.sanyan.chat.event.MessagePersistedEvent;
import com.sanyan.common.test.PostgresTestcontainerSupport;
import com.sanyan.llm.LlmApi;
import com.sanyan.memory.internal.rag.RagIndexWorker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.AopTestUtils;

import java.sql.Connection;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * Plan 3 Task K1：E2E final gate — 消息持久化事件 → 亲密度涨分 → DB 状态验证。
 *
 * <p>本 IT 启动完整 Spring 上下文（{@code SanyanApplication}），用 Testcontainers PG (with pgvector)
 * + Flyway V1-V6 拿到真实 schema，mock 掉外部依赖（LLM / Redis / ChatApi），
 * 用 {@link AopTestUtils#getTargetObject} 直接调 Listener 同步方法（绕过 @Async +
 * @TransactionalEventListener AFTER_COMMIT），验证核心链路：
 *
 * <ol>
 *   <li><b>user 消息涨分链路</b>：发布 {@link MessagePersistedEvent}（role=user）→
 *       {@link MessagePersistedListener#onMessagePersisted} →
 *       {@code IntimacyRecordService.recordEvent(MESSAGE_SENT, +1)} →
 *       DB: relationships(42,1).intimacy_score >= 1 + intimacy_logs 有 reason='MESSAGE_SENT' delta>0</li>
 *   <li><b>ai 消息不触发链路</b>：发布 MessagePersistedEvent（role=ai）→ listener 直接跳过 →
 *       DB: relationships 不存在（未懒创建）</li>
 * </ol>
 *
 * <h2>为什么直接调 Listener 方法而不是发事件</h2>
 * <p>真实路径走 @TransactionalEventListener(AFTER_COMMIT) + @Async：
 * <ul>
 *   <li>AFTER_COMMIT 要求事件在事务内发布，提交后触发——IT 里事务管理复杂，
 *       靠 TestTransaction.flagForCommit 或 propagation 拆分容易出错</li>
 *   <li>@Async 需要 Awaitility 轮询等待，引入额外不稳定性</li>
 * </ul>
 * 直接调 public 方法 == 同步执行 listener 逻辑，验证的是"业务逻辑串通"这个核心目标。
 * 事件订阅/异步本身已在 MessagePersistedListenerTest 单测里覆盖。
 *
 * <h2>为什么 mock ChatApi / LlmApi / StringRedisTemplate</h2>
 * <ul>
 *   <li><b>ChatApi</b>：MessagePersistedListener 调用 listRecentByUser 查最近消息，
 *       本 IT 不插消息，用 mock 返回空列表即可（不测 plot/quality 链路，只测涨分核心）</li>
 *   <li><b>LlmApi</b>：ConversationQualityEvaluator 依赖 LLM，CI 不能调外部 API</li>
 *   <li><b>StringRedisTemplate</b>：DailyBehaviorCounter 读写 Redis，mock 后
 *       consumedToday 返回 null（解析为 0），允许第一条消息正常 +1</li>
 * </ul>
 *
 * <h2>不验证的内容</h2>
 * <ul>
 *   <li>HTTP 层（没起 web server）</li>
 *   <li>真实 @Async / @TransactionalEventListener 时机（MessagePersistedListenerTest 覆盖）</li>
 *   <li>剧情节点 / AI 质量评估（各自有独立单测）</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@AutoConfigureTestDatabase(replace = NONE)
class MessageFlowE2EIT extends PostgresTestcontainerSupport {

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestcontainerSupport::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestcontainerSupport::username);
        registry.add("spring.datasource.password", PostgresTestcontainerSupport::password);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("spring.flyway.clean-disabled", () -> "false");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        // 关掉 Redis autoconfigure（已 mock StringRedisTemplate，不需要真连接）
        registry.add("spring.autoconfigure.exclude", () ->
                "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration");
        // 其他必填配置
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

    /** user 消息涨分测试用的 userId（高位，避免与 V6 seed 和 Plan2 IT 冲突）。 */
    private static final Long USER_ID_MSG = 9042L;
    /** ai 消息不触发测试用的 userId。 */
    private static final Long USER_ID_AI_MSG = 9043L;
    /** RelationshipFetchService 事务边界回归测试用的 userId。 */
    private static final Long USER_ID_FETCH = 9044L;
    /** 小婉 character_id（V6 seed 保证存在）。 */
    private static final Long CHARACTER_ID = 1L;

    // ============ Mocked external beans ============

    @MockBean
    private LlmApi llmApi;

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    /**
     * RagIndexWorker 有 @Scheduled(fixedDelay=10000) 任务，上下文启动后立即调度一次，
     * 因 mock 未 stub 会抛 NPE（ERROR 日志噪声）。用 @MockBean 替换禁用其调度，让日志干净。
     */
    @MockBean
    private RagIndexWorker ragIndexWorker;

    // ============ Beans under test ============

    @Autowired
    private MessagePersistedListener messagePersistedListener;

    @Autowired
    private RelationshipRepository relRepo;

    @Autowired
    private IntimacyLogRepository logRepo;

    @Autowired
    private RelationshipFetchService fetchService;

    @MockBean
    private ChatApi chatApi;

    @BeforeEach
    void seedFkFixtures() throws Exception {
        // V1 schema 有 FK：relationships.user_id → users.id，必须先 seed users 行。
        // 用 PostgresTestcontainerSupport.newConnection() JDBC 直连，避免依赖 Spring 事务上下文
        // （@BeforeEach 默认不在事务里运行，而 em.executeUpdate 需要活跃事务）。
        try (Connection conn = newConnection()) {
            conn.createStatement().execute(
                    "INSERT INTO users (id, phone, password, nickname) VALUES " +
                    "(" + USER_ID_MSG + ", 'e2e-fk-" + USER_ID_MSG + "', 'x', 'e2e-9042'), " +
                    "(" + USER_ID_AI_MSG + ", 'e2e-fk-" + USER_ID_AI_MSG + "', 'x', 'e2e-9043'), " +
                    "(" + USER_ID_FETCH + ", 'e2e-fk-" + USER_ID_FETCH + "', 'x', 'e2e-9044') " +
                    "ON CONFLICT (id) DO NOTHING"
            );
        }
    }

    @BeforeEach
    void setupRedisMock() {
        // DailyBehaviorCounter 走 redis.opsForValue().get(key) 和 increment/expire
        // mock 后 consumedToday 返回 null → 解析为 0 → 允许第一条消息正常 +1
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(valueOps.get(anyString())).thenReturn(null);
        lenient().when(valueOps.increment(anyString(), any(Long.class))).thenReturn(1L);
        lenient().when(stringRedisTemplate.expire(anyString(), any(Duration.class))).thenReturn(true);

        // ConsecutiveLoginService.recordLogin 走 redis.opsForHash().entries(key)。
        // mock 返回空 Map → 视为首日登录 → streak=1, isFirstToday=true → 触发 DAILY_LOGIN。
        // 用 doReturn 绕过 stringRedisTemplate.opsForHash() 的泛型限制（when().thenReturn 在 raw cast 时失败）。
        HashOperations hashOps = mock(HashOperations.class);
        org.mockito.Mockito.doReturn(hashOps).when(stringRedisTemplate).opsForHash();
        lenient().when(hashOps.entries(anyString())).thenReturn(java.util.Map.of());
    }

    @BeforeEach
    void setupChatApiMock() {
        // MessagePersistedListener 调用 chatApi.listRecentByUser 查最近消息（用于 plot/quality 评估）
        // 本 IT 不验证 plot/quality 链路，返回空列表避免 DB 查询依赖
        lenient().when(chatApi.listRecentByUser(any(), anyInt())).thenReturn(List.of());
    }

    @AfterEach
    void cleanupTestData() throws Exception {
        // 测试方法不加 @Transactional（因为 IntimacyRecordTransaction 用 REQUIRES_NEW，
        // 测试事务 + REQUIRES_NEW 会导致内层事务读不到外层未提交的 relationship 行）。
        // 改用 @AfterEach JDBC 直连删除测试数据，确保测试间隔离。
        try (Connection conn = newConnection()) {
            conn.createStatement().execute(
                    "DELETE FROM intimacy_logs WHERE user_id IN ("
                            + USER_ID_MSG + ", " + USER_ID_AI_MSG + ", " + USER_ID_FETCH + ")"
            );
            conn.createStatement().execute(
                    "DELETE FROM relationship_milestones WHERE user_id IN ("
                            + USER_ID_MSG + ", " + USER_ID_AI_MSG + ", " + USER_ID_FETCH + ")"
            );
            conn.createStatement().execute(
                    "DELETE FROM relationships WHERE user_id IN ("
                            + USER_ID_MSG + ", " + USER_ID_AI_MSG + ", " + USER_ID_FETCH + ")"
            );
        }
    }

    /**
     * 链路验证：user 消息事件 → 亲密度涨分 → DB 状态正确。
     *
     * <p>直接调 listener.onMessagePersisted（用 AopTestUtils 绕过 @Async 代理），
     * 验证：relationships 懒创建，intimacy_score >= 1；intimacy_logs 有 reason='MESSAGE_SENT' delta>0。
     */
    @Test
    void user_message_event_should_create_relationship_and_accumulate_intimacy() {
        MessagePersistedListener rawListener = AopTestUtils.getTargetObject(messagePersistedListener);
        rawListener.onMessagePersisted(
                new MessagePersistedEvent(99L, USER_ID_MSG, CHARACTER_ID, SenderType.USER));

        // 验证 relationships 被懒创建
        var rel = relRepo.findByUserIdAndCharacterId(USER_ID_MSG, CHARACTER_ID);
        assertThat(rel).as("user 消息 listener 应懒创建 relationship").isPresent();
        assertThat(rel.get().getIntimacyScore())
                .as("MESSAGE_SENT 应至少 +1，intimacy_score >= 1")
                .isGreaterThanOrEqualTo(1);

        // 验证 intimacy_logs 有审计记录
        var logs = logRepo.findTop10ByUserIdAndCharacterIdOrderByCreatedAtDesc(
                USER_ID_MSG, CHARACTER_ID);
        assertThat(logs).as("应有 intimacy_logs 审计行").isNotEmpty();
        assertThat(logs.get(0).getReason())
                .as("日志 reason 应为 MESSAGE_SENT")
                .isEqualTo("MESSAGE_SENT");
        assertThat(logs.get(0).getDelta())
                .as("日志 delta 应 > 0（未触发每日封顶）")
                .isPositive();
    }

    /**
     * 链路验证：ai 消息事件 → listener 直接跳过 → DB 无任何变更。
     *
     * <p>非 user role 应被 SenderType.USER.equalsIgnoreCase 过滤，不触发涨分，也不懒创建 relationship。
     */
    /**
     * 事务边界回归（K2 dogfood 暴露）：
     * 首次 fetchMyRelationship 必须既懒建关系又触发 DAILY_LOGIN 涨分，不抛 RELATIONSHIP_NOT_FOUND。
     *
     * <p>Bug 历史：RelationshipFetchService.fetchMyRelationship 早期标了 @Transactional，
     * 内部先 findOrCreate save（外层事务未 commit），随后调 IntimacyRecordTransaction.doRecord
     * (REQUIRES_NEW)。REQUIRES_NEW 会挂起外层事务并在新事务里再查 relationship —— 此时
     * 新事务读不到外层未提交的行，抛 RELATIONSHIP_NOT_FOUND(3002)。修复 = 去掉 fetchMyRelationship
     * 的 @Transactional，让 findOrCreate 用自己的事务先 commit 再调 doRecord。
     */
    @Test
    void fetchMyRelationship_should_lazy_create_and_trigger_daily_login_on_first_call() {
        var dto = fetchService.fetchMyRelationship(USER_ID_FETCH, CHARACTER_ID);

        assertThat(dto).as("首次调用应返回 DTO 而不是抛异常").isNotNull();
        assertThat(dto.userId()).isEqualTo(USER_ID_FETCH);
        assertThat(dto.characterId()).isEqualTo(CHARACTER_ID);
        // DAILY_LOGIN 触发后 score 应 > 0（具体涨多少取决于 streak 配置）
        assertThat(dto.intimacyScore())
                .as("首次登录应触发 DAILY_LOGIN，intimacy_score 应 > 0")
                .isPositive();

        var rel = relRepo.findByUserIdAndCharacterId(USER_ID_FETCH, CHARACTER_ID);
        assertThat(rel).as("relationship 应被懒创建").isPresent();
        assertThat(rel.get().getIntimacyScore())
                .as("DB 中的 intimacy_score 应与 DTO 一致")
                .isEqualTo(dto.intimacyScore());

        var logs = logRepo.findTop10ByUserIdAndCharacterIdOrderByCreatedAtDesc(
                USER_ID_FETCH, CHARACTER_ID);
        assertThat(logs).as("应有 intimacy_logs 审计行").isNotEmpty();
        assertThat(logs.stream().anyMatch(l -> "DAILY_LOGIN".equals(l.getReason())))
                .as("应至少有一行 reason=DAILY_LOGIN")
                .isTrue();
    }

    @Test
    void ai_message_event_should_be_ignored_with_no_db_changes() {
        MessagePersistedListener rawListener = AopTestUtils.getTargetObject(messagePersistedListener);
        rawListener.onMessagePersisted(
                new MessagePersistedEvent(100L, USER_ID_AI_MSG, CHARACTER_ID, SenderType.AI));

        // ai 消息不触发 listener，不懒创建 relationship
        var rel = relRepo.findByUserIdAndCharacterId(USER_ID_AI_MSG, CHARACTER_ID);
        assertThat(rel).as("ai 消息不应触发任何链路，relationship 不应存在").isEmpty();

        // 也不应有 intimacy_logs
        var logs = logRepo.findTop10ByUserIdAndCharacterIdOrderByCreatedAtDesc(
                USER_ID_AI_MSG, CHARACTER_ID);
        assertThat(logs).as("ai 消息不应产生 intimacy_logs").isEmpty();
    }
}
