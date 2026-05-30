package com.sanyan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanyan.common.test.PostgresTestcontainerSupport;
import com.sanyan.memory.internal.rag.RagIndexWorker;
import com.sanyan.user.internal.SmsCodeSendService;
import com.sanyan.user.internal.UserAuthIdentityEntity;
import com.sanyan.user.internal.UserAuthIdentityRepository;
import com.sanyan.user.internal.UserRepository;
import com.sanyan.user.internal.oauth.AppleTokenVerifier;
import com.sanyan.user.internal.oauth.Provider;
import com.sanyan.user.internal.oauth.VerifiedIdentity;
import com.sanyan.user.internal.oauth.WechatCodeVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 第三方登录全链路集成测试（第三方登录 Task 12，收尾验收）。
 *
 * <p><b>与 {@code AuthControllerIT} 的本质区别：</b>AuthControllerIT 是 {@code @WebMvcTest} +
 * {@code @MockBean OauthLoginService/OauthBindService} 的<b>控制器切片</b>，验的是 HTTP ↔ JSON 映射；
 * 本 IT 启动<b>完整 Spring 上下文</b>（{@code SanyanApplication}），打<b>真 service 编排 + 真 Postgres
 * (Testcontainer + Flyway V1→V12) + 真 Redis (Testcontainer) 支撑的 KvCache + 真 BindTicketUtil /
 * JwtUtil / SmsCodeSendService</b>，端到端走完 {@code challenge → login → bind-phone} HTTP 全链路，
 * 只 {@code @MockBean} 替换<b>最外层第三方网络依赖</b>（{@link AppleTokenVerifier} /
 * {@link WechatCodeVerifier}）——它们要真连苹果 / 微信，CI 无法依赖。其余全真。
 *
 * <h2>为什么用真 Redis 而不像其它 E2E IT mock StringRedisTemplate</h2>
 * <p>本链路的安全核心就在 KvCache 的<b>原子一次性消费</b>上：nonce 防重放（getAndDelete）、
 * bindTicket jti 一次性锁（setIfAbsent）、短信码原子取出（getAndDelete）。mock 掉 Redis 就等于
 * 把要验的东西验空了。故用 {@code GenericContainer(redis:7-alpine)} 起真 Redis，让这三处一次性语义
 * 真实生效（参考 character-core {@code DailyBehaviorCounterIT} 的 Redis 容器模式，但本 IT 不 exclude
 * RedisAutoConfiguration，要真连）。
 *
 * <h2>短信码怎么取</h2>
 * <p>不 mock SmsCodeSendService。test 环境短信走 {@code StubSmsSender}（只打日志不真发），但验证码
 * <b>真写进了 KvCache</b>（key={@code sms:code:{phone}}，TTL 5min）。本 IT 直接调
 * {@code /api/auth/sms/send} 触发真实发码，再用 {@code StringRedisTemplate} 从该 key 读出明文码，
 * 喂给后续接口——完整跑通真实短信发 / 校验路径，无任何 mock。
 *
 * <h2>放在 bootstrap 而非 sanyan-user-core</h2>
 * <p>与 {@link UserAuthIdentityRepositoryIT} 同理：V12 migration 文件在 bootstrap 的
 * {@code db/migration} 资源目录，只有 bootstrap test classpath 能让 Flyway 跑出 V12；bootstrap 同时
 * compile 依赖 sanyan-user-core，故 controller / entity / repo / verifier 在此可见。本任务计划建议放
 * user-core/oauth，但 user-core 无 test resources / 无 Flyway，无法起真库全链路，遂沿用既有惯例。
 *
 * <h2>覆盖场景</h2>
 * <ol>
 *   <li>首次第三方登录建号全链路（challenge → login(APPLE 新身份) needBind → bind-phone 建号发 token，
 *       查库断言 user + user_auth_identity 各落一行）</li>
 *   <li>已绑身份直接登录（同 provider+externalId 再 login 命中，needBind=false，user / identity 计数不变）</li>
 *   <li>nonce 防重放（用过的 nonce 再 login 失败）</li>
 *   <li>bindTicket 一次性（绑成功后同 ticket 再 bind-phone → BIND_TICKET_USED 1013）</li>
 *   <li>已有手机号账号 needMerge（先注册普通账号 → 第三方 login 拿 ticket → bind 同手机号不传密码 →
 *       needMergeAuth=true，身份不挂上去）</li>
 *   <li>微信 provider 建号链路（login(WECHAT) → bind-phone 建号发 token）</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = NONE)
// 基础 application.yml exclude 了 RedisAutoConfiguration（多数 E2E IT mock Redis），本 IT 要真连，
// 用 oauthe2e profile 清空该 exclude，让 RedisAutoConfiguration 回来按 spring.data.redis.* 连 Testcontainer Redis。
@ActiveProfiles("oauthe2e")
class OauthEndToEndIT extends PostgresTestcontainerSupport {

    /** 真 Redis 容器，支撑 KvCache 的 nonce / jti / 短信码一次性消费语义。 */
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry registry) {
        // 真 Postgres（pgvector 镜像）+ Flyway V1→V12 跑完整 schema
        registry.add("spring.datasource.url", PostgresTestcontainerSupport::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestcontainerSupport::username);
        registry.add("spring.datasource.password", PostgresTestcontainerSupport::password);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("spring.flyway.clean-disabled", () -> "false");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        // 真 Redis：指向 Testcontainer（oauthe2e profile 已清空 RedisAutoConfiguration 的 exclude）
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        // sanyan.* 必填占位（与 src/test/resources/application.yml 对齐）
        registry.add("sanyan.jwt.secret", () ->
                "test-secret-key-at-least-256-bits-long-for-hmac-sha-testing");
        registry.add("sanyan.jwt.expiration-days", () -> "1");
        registry.add("sanyan.oauth.bind-ticket.secret", () ->
                "test-bind-ticket-secret-at-least-256-bits-long-for-hmac-testing");
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


    @MockBean private AppleTokenVerifier appleTokenVerifier;
    @MockBean private WechatCodeVerifier wechatCodeVerifier;

    /**
     * RagIndexWorker 在上下文启动后被 {@code @Scheduled} 调度，会读未 stub 的依赖打 ERROR 噪声。
     * 本 IT 不验 RAG 链路，直接 mock 掉禁用其调度（参考 Plan2EndToEndIT）。
     */
    @MockBean private RagIndexWorker ragIndexWorker;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private StringRedisTemplate redis;
    @Autowired private UserRepository userRepository;
    @Autowired private UserAuthIdentityRepository identityRepository;

    @BeforeEach
    void flushRedis() {
        // 清空 Redis，防止用例间 nonce / 短信码 / jti 互相污染
        redis.getRequiredConnectionFactory().getConnection().serverCommands().flushDb();
    }

    // ============ 场景 1：首次第三方登录建号全链路 ============

    @Test
    void scenario1_firstTimeAppleLogin_createsUserAndIdentity() throws Exception {
        String externalId = "apple-sub-new-001";
        String phone = "13800010001";
        // mock 最外层 Apple 校验器：任何 token + 任何 nonce 都返回这个新身份
        when(appleTokenVerifier.verify(any(), any()))
                .thenReturn(new VerifiedIdentity(externalId, null));

        long usersBefore = userRepository.count();
        long identitiesBefore = identityRepository.count();

        // 1) challenge 拿真 nonce
        String nonce = issueNonce();

        // 2) login(APPLE, 新身份, nonce) → needBind + bindTicket
        MvcResult loginResult = oauthLogin(Provider.APPLE, "apple-identity-token", nonce)
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.needBind").value(true))
                .andExpect(jsonPath("$.data.token").doesNotExist())
                .andReturn();
        String bindTicket = extractBindTicket(loginResult);

        // 3) 真实发码 + 从 Redis 取明文短信码
        sendSms(phone);
        String code = readSmsCode(phone);

        // 4) bind-phone(bindTicket, 新手机号, 正确短信码) → 拿 token + userId
        MvcResult bindResult = oauthBindPhone(bindTicket, phone, code, null)
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.needBind").value(false))
                .andExpect(jsonPath("$.data.needMergeAuth").value(false))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.userId").isNumber())
                .andReturn();
        long userId = ((Number) jsonValue(bindResult, "$.data.userId")).longValue();

        // 查库断言：user 表建了号、user_auth_identity 落了 (APPLE, externalId, userId) 一行
        assertThat(userRepository.count())
                .as("应新建一个 user").isEqualTo(usersBefore + 1);
        assertThat(userRepository.findById(userId)).isPresent();
        assertThat(userRepository.findById(userId).get().getPhone()).isEqualTo(phone);

        assertThat(identityRepository.count())
                .as("应新建一条 identity").isEqualTo(identitiesBefore + 1);
        Optional<UserAuthIdentityEntity> identity =
                identityRepository.findByProviderAndExternalId(Provider.APPLE, externalId);
        assertThat(identity).isPresent();
        assertThat(identity.get().getUserId()).isEqualTo(userId);
        assertThat(identity.get().getProvider()).isEqualTo(Provider.APPLE);
    }

    // ============ 场景 2：已绑身份直接登录（不新建） ============

    @Test
    void scenario2_alreadyBoundIdentity_logsInDirectly_noNewRows() throws Exception {
        String externalId = "apple-sub-bound-002";
        String phone = "13800010002";
        when(appleTokenVerifier.verify(any(), any()))
                .thenReturn(new VerifiedIdentity(externalId, null));

        // 先把身份建好（复用场景 1 的建号链路）
        String nonce1 = issueNonce();
        String bindTicket = extractBindTicket(
                oauthLogin(Provider.APPLE, "tok", nonce1).andReturn());
        sendSms(phone);
        MvcResult bindResult = oauthBindPhone(bindTicket, phone, readSmsCode(phone), null)
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andReturn();
        long boundUserId = ((Number) jsonValue(bindResult, "$.data.userId")).longValue();

        long usersAfterBind = userRepository.count();
        long identitiesAfterBind = identityRepository.count();

        // 同 provider + externalId + 新 nonce 再 login → 命中直接登录
        String nonce2 = issueNonce();
        oauthLogin(Provider.APPLE, "tok-again", nonce2)
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.needBind").value(false))
                .andExpect(jsonPath("$.data.userId").value(boundUserId))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.bindTicket").doesNotExist());

        // 没有新建 user / identity
        assertThat(userRepository.count())
                .as("命中登录不应新建 user").isEqualTo(usersAfterBind);
        assertThat(identityRepository.count())
                .as("命中登录不应新建 identity").isEqualTo(identitiesAfterBind);
    }

    // ============ 场景 3：nonce 防重放 ============

    @Test
    void scenario3_replayedNonce_isRejected() throws Exception {
        when(appleTokenVerifier.verify(any(), any()))
                .thenReturn(new VerifiedIdentity("apple-sub-replay-003", null));

        String nonce = issueNonce();
        // 第一次 login 消费掉 nonce（成功，needBind）
        oauthLogin(Provider.APPLE, "tok", nonce)
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.needBind").value(true));

        // 同 nonce 再 login → nonce 已消费，失败（OAUTH_VERIFY_FAILED）
        oauthLogin(Provider.APPLE, "tok", nonce)
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(1010));
    }

    // ============ 场景 4：bindTicket 一次性 ============

    @Test
    void scenario4_reusedBindTicket_isRejectedAsUsed() throws Exception {
        String phone = "13800010004";
        when(appleTokenVerifier.verify(any(), any()))
                .thenReturn(new VerifiedIdentity("apple-sub-ticket-004", null));

        String bindTicket = extractBindTicket(
                oauthLogin(Provider.APPLE, "tok", issueNonce()).andReturn());

        // 第一次绑成功
        sendSms(phone);
        oauthBindPhone(bindTicket, phone, readSmsCode(phone), null)
                .andExpect(jsonPath("$.data.token").isNotEmpty());

        // 用同一 bindTicket 再绑 → jti 已消费，BIND_TICKET_USED(1013)
        sendSms("13800010044");
        oauthBindPhone(bindTicket, "13800010044", readSmsCode("13800010044"), null)
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(1013));
    }

    // ============ 场景 5：已有手机号账号 needMerge ============

    @Test
    void scenario5_phoneAlreadyRegistered_noPassword_returnsNeedMerge() throws Exception {
        String phone = "13800010005";
        // 先用手机号注册一个普通有密码账号（真实注册链路）
        sendSms(phone);
        registerUser(phone, readSmsCode(phone), "pwd123456")
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.token").isNotEmpty());

        long usersAfterRegister = userRepository.count();
        long identitiesAfterRegister = identityRepository.count();

        // 第三方 login(新 externalId) 拿 bindTicket
        when(appleTokenVerifier.verify(any(), any()))
                .thenReturn(new VerifiedIdentity("apple-sub-merge-005", null));
        String bindTicket = extractBindTicket(
                oauthLogin(Provider.APPLE, "tok", issueNonce()).andReturn());

        // bind-phone 同手机号、不传密码 → needMergeAuth=true，不挂身份
        sendSms(phone);
        oauthBindPhone(bindTicket, phone, readSmsCode(phone), null)
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.needMergeAuth").value(true))
                .andExpect(jsonPath("$.data.token").doesNotExist());

        // 没把第三方身份挂上去，user 数也没变
        assertThat(identityRepository.count())
                .as("needMerge 不应挂任何 identity").isEqualTo(identitiesAfterRegister);
        assertThat(userRepository.count())
                .as("needMerge 不应新建 user").isEqualTo(usersAfterRegister);
        assertThat(identityRepository.findByProviderAndExternalId(Provider.APPLE, "apple-sub-merge-005"))
                .as("身份不应被绑定").isEmpty();
    }

    // ============ 场景 6：微信 provider 建号链路 ============

    @Test
    void scenario6_wechatLogin_createsUserAndIdentity() throws Exception {
        String unionid = "wx-union-006";
        String openid = "wx-openid-006";
        String phone = "13800010006";
        // 微信不消费 nonce；externalId=unionid，rawOpenid=openid
        when(wechatCodeVerifier.verify(any(), any()))
                .thenReturn(new VerifiedIdentity(unionid, openid));

        long usersBefore = userRepository.count();
        long identitiesBefore = identityRepository.count();

        // 微信 login（nonce 可空）→ needBind + bindTicket
        String bindTicket = extractBindTicket(
                oauthLogin(Provider.WECHAT, "wx-code", null)
                        .andExpect(jsonPath("$.data.needBind").value(true))
                        .andReturn());

        sendSms(phone);
        MvcResult bindResult = oauthBindPhone(bindTicket, phone, readSmsCode(phone), null)
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andReturn();
        long userId = ((Number) jsonValue(bindResult, "$.data.userId")).longValue();

        assertThat(userRepository.count()).isEqualTo(usersBefore + 1);
        assertThat(identityRepository.count()).isEqualTo(identitiesBefore + 1);
        Optional<UserAuthIdentityEntity> identity =
                identityRepository.findByProviderAndExternalId(Provider.WECHAT, unionid);
        assertThat(identity).isPresent();
        assertThat(identity.get().getUserId()).isEqualTo(userId);
        assertThat(identity.get().getRawOpenid())
                .as("微信身份应记录 rawOpenid").isEqualTo(openid);
    }

    // ============ HTTP 调用 helpers ============

    private String issueNonce() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/oauth/challenge")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.nonce").isNotEmpty())
                .andReturn();
        return (String) jsonValue(result, "$.data.nonce");
    }

    private org.springframework.test.web.servlet.ResultActions oauthLogin(
            Provider provider, String credential, String nonce) throws Exception {
        String body = objectMapper.writeValueAsString(new LoginPayload(provider, credential, nonce));
        return mockMvc.perform(post("/api/auth/oauth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions oauthBindPhone(
            String bindTicket, String phone, String code, String password) throws Exception {
        String body = objectMapper.writeValueAsString(
                new BindPayload(bindTicket, phone, code, password));
        return mockMvc.perform(post("/api/auth/oauth/bind-phone")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions registerUser(
            String phone, String code, String password) throws Exception {
        String body = objectMapper.writeValueAsString(
                new RegisterPayload(phone, code, password, null));
        return mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    private void sendSms(String phone) throws Exception {
        // SmsCodeSendService 对同一手机号有 60s 发送节流（key=sms:rate:{phone}）。同一用例里需对同一号码
        // 二次发码（如先注册再 oauth 绑同号）时，真实场景是间隔分钟级；测试里清掉节流 key 模拟时间流逝，
        // 其余发码逻辑（生成码 / 写 KvCache）保持真实。
        redis.delete(SmsCodeSendService.RATE_PREFIX + phone);
        String body = objectMapper.writeValueAsString(new SmsPayload(phone));
        mockMvc.perform(post("/api/auth/sms/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    /** 从真 Redis 取出 StubSmsSender 发出的明文短信码（key = sms:code:{phone}）。 */
    private String readSmsCode(String phone) {
        String key = SmsCodeSendService.CODE_PREFIX + phone;
        String code = redis.opsForValue().get(key);
        assertThat(code)
                .as("发码后 KvCache 应存在 " + key)
                .isNotNull();
        return code;
    }

    private String extractBindTicket(MvcResult result) {
        Object ticket = jsonValue(result, "$.data.bindTicket");
        assertThat(ticket).as("login 应返回 bindTicket").isNotNull();
        return (String) ticket;
    }

    private Object jsonValue(MvcResult result, String path) {
        try {
            return com.jayway.jsonpath.JsonPath.read(
                    result.getResponse().getContentAsString(), path);
        } catch (Exception e) {
            throw new RuntimeException("读取 JSON path 失败: " + path, e);
        }
    }

    // ============ 请求体（用 record 直接序列化，provider 走 Jackson enum） ============

    private record LoginPayload(Provider provider, String credential, String nonce) {}

    private record BindPayload(String bindTicket, String phone, String code, String password) {}

    private record RegisterPayload(String phone, String code, String password, String nickname) {}

    private record SmsPayload(String phone) {}
}
