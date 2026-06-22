package com.sanyan.memory.internal.item;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanyan.llm.LlmApi;
import com.sanyan.llm.LlmTaskType;
import com.sanyan.llm.dto.ChatMessage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P-T3 端到端验证（真调 DeepSeek）：验证"在 prompt 注入今天日期后，LLM 真能把相对说法
 * （'周三'）换算成绝对 ISO 事件日期"，从而治掉"永远后天"。
 *
 * <p><b>守卫</b>：{@code @EnabledIfEnvironmentVariable(DEEPSEEK_API_KEY)}——无 key 时整类跳过，
 * 不报失败（CI / 本地默认不跑）。<b>本测试类由协调者手动带 key 运行，实现子代理不跑（无 key）。</b>
 * 手动验证命令：
 * <pre>
 *   DEEPSEEK_API_KEY=&lt;key&gt; mvn -pl business_packages/sanyan-memory-core -am test \
 *       -Dtest=MemoryItemExtractServiceE2ETest -Dgroups=e2e -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 *
 * <p><b>为什么不复用 sanyan-llm-core 的真实 LlmApi</b>：Maven Enforcer 的 banned-dependencies
 * 规则禁止任何 {@code -core} 模块依赖其它 {@code -core}（架构边界守护，见 java-backend §4）。
 * memory-core 只能依赖 {@code sanyan-llm-api} 契约。因此本 e2e 在测试内手搓一个最小
 * {@link LlmApi}（直连 DeepSeek 的 OpenAI 兼容 {@code /chat/completions}），仅用于验证"真 LLM
 * 在知道今天日期后的换算能力"，不接 DB（repository 用 Mockito 桩掉）。
 *
 * <p><b>固定真实当下日期</b>：用真实系统时区的 {@link Clock}，"今天"= 实际运行当天，
 * 断言用"相对今天的最近未来周三"动态计算（避免写死日期导致次日就过期）。
 */
@Tag("e2e")
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
@ExtendWith(MockitoExtension.class)
class MemoryItemExtractServiceE2ETest {

    @Mock MemoryItemRepository repository;
    @Mock ApplicationEventPublisher events;

    /** 真实系统时区时钟——"今天"= 实际运行当天，让"周三"换算有真实参照。 */
    private final Clock clock = Clock.system(ZoneId.of("Asia/Shanghai"));

    @Test
    void deepseek_should_resolve_relative_weekday_to_absolute_iso_date() {
        LlmApi realDeepSeek = new DeepSeekTestLlmApi(System.getenv("DEEPSEEK_API_KEY"));
        MemoryItemExtractService service =
                new MemoryItemExtractService(realDeepSeek, repository, events, clock);

        // 去重上下文为空；save 原样返回（回填一个 id 即可，不接 DB）
        when(repository.findTop20ByUserIdAndCharacterIdAndStatusOrderByIdDesc(any(), any(), any()))
                .thenReturn(List.of());
        lenient().when(repository.save(any())).thenAnswer(inv -> {
            MemoryItemEntity e = inv.getArgument(0);
            e.setId(999L);
            return e;
        });

        // 真调 DeepSeek：今天注入 prompt，"周三"应被换算成最近未来周三的绝对日期
        service.extract(1L, 1L, "周三有个面试好紧张", 100L);

        // LLM 可能从这句话同时抽出 PLAN_EVENT（周三面试）+ EMOTION（紧张），save 调用 ≥1 次。
        // 捕获所有落库条目，挑出 PLAN_EVENT 校验其 salientAt = 那个周三 09:00（Asia/Shanghai）。
        ArgumentCaptor<MemoryItemEntity> saved = ArgumentCaptor.forClass(MemoryItemEntity.class);
        verify(repository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        MemoryItemEntity e = saved.getAllValues().stream()
                .filter(it -> it.getKind() == MemoryItemKind.PLAN_EVENT)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "LLM 未抽出 PLAN_EVENT，实际落库条目=" + saved.getAllValues().stream()
                                .map(it -> it.getKind() + ":" + it.getContent() + "@" + it.getSalientAt())
                                .toList()));

        assertThat(e.getSalientAt()).isNotNull();

        // 期望：以"今天"为基准、最近的未来（含今天）周三 09:00
        LocalDate today = LocalDate.now(clock);
        LocalDate expectedWednesday = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.WEDNESDAY));
        Instant expectedSalientAt = expectedWednesday.atTime(9, 0)
                .atZone(clock.getZone()).toInstant();

        // 打印实际结果供人工判读（LLM 偶有偏差时便于排查 prompt 措辞）
        System.out.println("[P-T3 e2e] today=" + today + " (" + today.getDayOfWeek() + ")"
                + " expectedWednesday=" + expectedWednesday
                + " actualSalientAt=" + e.getSalientAt());

        // 核心断言：LLM 知道"今天周几"后，把"周三"算成了正确的那一天 09:00，
        // 而不是降级成"次日 09:00"（治"永远后天"的直接证据）。
        assertThat(e.getSalientAt()).isEqualTo(expectedSalientAt);
    }

    /**
     * 测试内最小 DeepSeek 客户端：直连 OpenAI 兼容 {@code /chat/completions}。
     * 仅本 e2e 用，避免依赖 {@code sanyan-llm-core}（被 enforcer 禁止）。memory-core 测试类路径
     * 没有 spring-web，故用 JDK 自带 {@link HttpClient} + Jackson（已是本模块依赖）手搓。
     * BACKGROUND 任务按生产配置只传 model + messages（不加 temperature 等，保结构化输出稳定）。
     */
    private static final class DeepSeekTestLlmApi implements LlmApi {
        private static final ObjectMapper MAPPER = new ObjectMapper();
        private final String apiKey;
        private final String model;
        private final String baseUrl;
        private final HttpClient http;

        DeepSeekTestLlmApi(String apiKey) {
            this.apiKey = apiKey;
            // 与生产默认一致；如服务器用别的 model/base-url，手动跑时用环境变量覆盖
            this.model = System.getenv().getOrDefault("DEEPSEEK_MODEL", "deepseek-chat");
            this.baseUrl = System.getenv().getOrDefault("DEEPSEEK_BASE_URL", "https://api.deepseek.com");
            this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        }

        @Override
        public String chat(LlmTaskType taskType, List<ChatMessage> messages) {
            try {
                List<Map<String, String>> raw = messages.stream()
                        .map(m -> Map.of("role", m.role(), "content", m.content()))
                        .toList();
                byte[] body = MAPPER.writeValueAsBytes(Map.of("model", model, "messages", raw));
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/chat/completions"))
                        .timeout(Duration.ofSeconds(60))
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() / 100 != 2) {
                    throw new IllegalStateException("DeepSeek HTTP " + resp.statusCode() + ": " + resp.body());
                }
                JsonNode root = MAPPER.readTree(resp.body());
                return root.path("choices").get(0).path("message").path("content").asText();
            } catch (Exception e) {
                throw new IllegalStateException("DeepSeek e2e 调用失败", e);
            }
        }
    }
}
