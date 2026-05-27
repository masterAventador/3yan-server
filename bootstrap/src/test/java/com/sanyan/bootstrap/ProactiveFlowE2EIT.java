package com.sanyan.bootstrap;

import com.sanyan.chat.ChatApi;
import com.sanyan.common.test.PostgresTestcontainerSupport;
import com.sanyan.llm.LlmApi;
import com.sanyan.llm.LlmTaskType;
import com.sanyan.memory.event.MemoryItemScheduledEvent;
import com.sanyan.memory.internal.item.MemoryItemEntity;
import com.sanyan.memory.internal.item.MemoryItemKind;
import com.sanyan.memory.internal.item.MemoryItemRepository;
import com.sanyan.memory.internal.item.MemoryItemStatus;
import com.sanyan.memory.internal.rag.RagIndexWorker;
import com.sanyan.proactive.internal.EventPendingRepository;
import com.sanyan.proactive.internal.EventStatus;
import com.sanyan.proactive.internal.EventType;
import com.sanyan.proactive.internal.ProactiveScheduler;
import com.sanyan.proactive.trigger.MemoryItemScheduledListener;
import org.junit.jupiter.api.AfterEach;
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

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * Plan 4 端到端 final gate（spec §5.1 全链路）：
 * memory_item(PLAN_EVENT, PENDING) → MemoryItemScheduledListener 排 events_pending(C_EVENT_FOLLOWUP,
 * SCHEDULED, 已到期) → ProactiveScheduler.poll() 领取调度 → 频率门控放行 → EventFollowupGenerator
 * 生成文案 → ChatApi.deliverProactiveMessage 投递 → 状态回写。
 *
 * <p>三处断言：① events_pending=SENT ② deliverProactiveMessage 被调 ③ memory_item=DONE。
 *
 * <p>参照 {@link MessageFlowE2EIT}：webEnvironment=NONE + Testcontainers PG + Flyway 真 schema，
 * mock LlmApi / StringRedisTemplate / RagIndexWorker / ChatApi。用 {@link AopTestUtils#getTargetObject}
 * 直接调 listener 同步方法（绕过 @Async + @TransactionalEventListener AFTER_COMMIT 时机）。
 *
 * <h2>为什么直接调 listener 而不发事件 + 为什么 poll 后要 await</h2>
 * <ul>
 *   <li>{@link MemoryItemScheduledListener#onMemoryItemScheduled} 是 @Async + AFTER_COMMIT，IT 里
 *       事务/异步时机不可控，直接调原始对象的 public 方法等价于同步执行其排期逻辑（事件订阅本身在单测覆盖）。</li>
 *   <li>{@link ProactiveScheduler#poll} → claimAndDispatch → {@code ProactiveDispatcher.dispatch}（@Async），
 *       poll 立即返回、终态 save / markMemoryItemDone 发生在 worker 线程，故 poll 后用 Awaitility 轮询
 *       等 worker 完成再断言。</li>
 * </ul>
 *
 * <h2>为什么 mock 这四个 bean</h2>
 * <ul>
 *   <li><b>ChatApi</b>：E2E 无 WS session（webEnvironment=NONE），真实投递会走 L2 超时→L3 离线推送，
 *       无意义。mock deliverProactiveMessage 返落库 messageId，真实投递留给 chat-core 自己的 IT。</li>
 *   <li><b>LlmApi</b>：文案生成依赖 LLM，CI 不能调外部 API，返固定串。</li>
 *   <li><b>StringRedisTemplate</b>：FrequencyGate 经 KvCache 读写 Redis 计当日已发数，mock 后
 *       get 返 null（解析为 0）、increment 返 1，配合 quiet-hours 关闭 → 门控放行。</li>
 *   <li><b>RagIndexWorker</b>：有 @Scheduled(fixedDelay=10000)，未 stub 会抛 NPE 噪声，mock 禁用。</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@AutoConfigureTestDatabase(replace = NONE)
class ProactiveFlowE2EIT extends PostgresTestcontainerSupport {

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
        // 其他必填配置（照抄 MessageFlowE2EIT）
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
        // 关闭免打扰：start=end=0 → isQuietHours() 恒 false（不受测试运行的 wall-clock 影响）
        registry.add("sanyan.proactive.quiet-hours.start", () -> "0");
        registry.add("sanyan.proactive.quiet-hours.end", () -> "0");
        // 本 IT 测 stage 0（findOrCreateRelationship 懒创建 → currentStage 默认 0）的 C_EVENT_FOLLOWUP
        // 门控放行。ProactiveProperties.scenesByStage 默认空 Map，FrequencyGate 读 null 拒所有场景，
        // 故 IT 自注入 stage 0 的 event-followup=true（main application.yml 生产配置不依赖此处）。
        // dailyCapByStage 用默认 [2,3,4,5,6]（stage 0 cap=2，mock 当日已发=0 不触顶），无需注入。
        registry.add("sanyan.proactive.scenes-by-stage[0].event-followup", () -> "true");
    }

    /** 主动消息链路测试用 userId（高位避免与 V6 seed / 其他 IT 冲突）。 */
    private static final Long USER_ID = 9051L;
    /** 小婉 character_id（V6 seed 保证存在）。 */
    private static final Long CHARACTER_ID = 1L;

    // ============ Mocked external beans ============

    @MockBean
    private LlmApi llmApi;

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    @MockBean
    private RagIndexWorker ragIndexWorker;

    @MockBean
    private ChatApi chatApi;

    // ============ Beans under test ============

    @Autowired
    private MemoryItemScheduledListener scheduledListener;

    @Autowired
    private ProactiveScheduler proactiveScheduler;

    @Autowired
    private EventPendingRepository eventRepo;

    @Autowired
    private MemoryItemRepository memoryItemRepo;

    @BeforeEach
    void seedFkFixtures() throws Exception {
        // relationships.user_id → users.id FK：dispatcher 走 findOrCreateRelationship 会插 relationships，
        // 必须先 seed users 行（character_id=1 由 V1 seed + V6 update 保证存在）。
        // memory_item / events_pending 本身无外键，不用 seed。
        try (Connection conn = newConnection()) {
            conn.createStatement().execute(
                    "INSERT INTO users (id, phone, password, nickname) VALUES "
                            + "(" + USER_ID + ", 'e2e-proactive-" + USER_ID + "', 'x', 'e2e-9051') "
                            + "ON CONFLICT (id) DO NOTHING"
            );
        }
    }

    @BeforeEach
    void setupMocks() {
        // FrequencyGate 经 KvCache → StringRedisTemplate 计当日已发数：
        // get 返 null（解析 0）、increment 返 1 → 当日上限不触顶，门控放行。
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(valueOps.get(anyString())).thenReturn(null);
        lenient().when(valueOps.increment(anyString())).thenReturn(1L);
        lenient().when(stringRedisTemplate.expire(anyString(), any(Duration.class))).thenReturn(true);

        // 文案生成（USER_FACING）返固定串
        lenient().when(llmApi.chat(any(LlmTaskType.class), any())).thenReturn("面试顺利吗？");

        // 投递 mock：返回落库 messageId（chat-core DeliveryService 的真实投递另有 IT）
        lenient().when(chatApi.deliverProactiveMessage(any(), any(), any())).thenReturn(List.of(7777L));
    }

    @AfterEach
    void cleanupTestData() throws Exception {
        try (Connection conn = newConnection()) {
            conn.createStatement().execute("DELETE FROM events_pending WHERE user_id = " + USER_ID);
            conn.createStatement().execute("DELETE FROM memory_item WHERE user_id = " + USER_ID);
            conn.createStatement().execute("DELETE FROM relationships WHERE user_id = " + USER_ID);
        }
    }

    /**
     * 全链路：PLAN_EVENT 落库 → 排 C_EVENT_FOLLOWUP → 调度 → 门控放行 → 生成 → 投递 → 状态回写。
     */
    @Test
    void plan_event_should_flow_to_sent_event_and_done_memory_item() {
        // 1) memory_item 落一条 PLAN_EVENT(PENDING)，salientAt 设过去确保 scheduler 立即取到
        MemoryItemEntity item = new MemoryItemEntity();
        item.setUserId(USER_ID);
        item.setCharacterId(CHARACTER_ID);
        item.setKind(MemoryItemKind.PLAN_EVENT);
        item.setContent("周三下午有个面试");
        item.setSalientAt(Instant.now().minusSeconds(60));
        long itemId = memoryItemRepo.save(item).getId();

        // 2) 直接调 listener（AopTestUtils 绕过 @Async + AFTER_COMMIT）排 events_pending
        MemoryItemScheduledListener rawListener = AopTestUtils.getTargetObject(scheduledListener);
        rawListener.onMemoryItemScheduled(new MemoryItemScheduledEvent(
                itemId, USER_ID, CHARACTER_ID, "PLAN_EVENT", Instant.now().minusSeconds(60)));

        // 排期完成断言：存在一条 C_EVENT_FOLLOWUP / SCHEDULED
        assertThat(eventRepo.findAll())
                .as("listener 应排一条 C_EVENT_FOLLOWUP / SCHEDULED 事件")
                .anyMatch(e -> e.getUserId().equals(USER_ID)
                        && e.getEventType() == EventType.C_EVENT_FOLLOWUP
                        && e.getStatus() == EventStatus.SCHEDULED);

        // 3) 调度一轮（直接调 poll，绕过 @Scheduled；dispatch 是 @Async，下面 await worker 完成）
        proactiveScheduler.poll();

        // 4-① events_pending=SENT（dispatch 在 worker 线程标终态，轮询等其完成）
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(eventRepo.findAll())
                        .as("调度+门控放行+投递后，事件应标 SENT")
                        .anyMatch(e -> e.getUserId().equals(USER_ID)
                                && e.getEventType() == EventType.C_EVENT_FOLLOWUP
                                && e.getStatus() == EventStatus.SENT));

        // 4-② deliverProactiveMessage 被调
        verify(chatApi).deliverProactiveMessage(any(), any(), any());

        // 4-③ memory_item=DONE（dispatcher 投递成功后 best-effort 回标，依赖 Phase J/K markMemoryItemDone 链路）
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(memoryItemRepo.findById(itemId).orElseThrow().getStatus())
                        .as("主动消息投递成功后，memory_item 应被回标 DONE")
                        .isEqualTo(MemoryItemStatus.DONE));
    }
}
