# AI 主动聊天质量修复 · 第一批（prompt 工程层 + 对空降频）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修掉 AI（小婉）主动推送最扎眼的三类毛病——旧事当今天 / 复读机 / 满屏"笨蛋"——并让对空狂推自动收敛。

**Architecture:** 六个独立 task，覆盖 4 个业务模块 + 1 个基础模块。核心思路：(1) 给主动推送的 prompt 注入"现在几点"和"那段记忆多久以前"，让 LLM 有时间锚点；(2) 把"最近已主动发过的内容"喂回 prompt + 在解码层加反重复参数，双管齐下压复读；(3) 人设里约束称呼频率；(4) 在 `FrequencyGate` 这个唯一闸口加"互动退避"，用户连续不理就停早晚安。时间/相对时间格式化抽到 `sanyan-common-util` 供主对话与主动推送复用（消除与 chat-core `AiService` 的重复）。

**Tech Stack:** Java 21、Spring Boot 3.5、Maven 多模块、JUnit 5 + Mockito + AssertJ、Redis(KvCache)、Postgres。LLM 走 DeepSeek（OpenAI 兼容协议）。

---

## ⛔ 全局执行约束（每个 task 都适用）

1. **TDD 铁律**：先写测试 → 跑 → **亲眼看到失败** → 写最小实现 → 跑 → 通过 → 重构。没有失败的测试就不许写生产代码。
2. **测试粒度**：每个 task 只跑**它所改动模块**的测试 + 该模块 `mvn -q -pl <module> test`。本批不涉及 foundation 下沉影响全量的场景，**除 Task 2/Task 4 改动了 `sanyan-common-util`（基础层）必须额外跑依赖方** 外，其余只跑本模块。具体命令见各 task。
3. **不部署**：所有 task 完成、final gate 通过后**停在提交**，等用户明确说"部署"。
4. **提交信息中文**，conventional 前缀可英文。**不要** Co-Authored-By / AI 署名。
5. **静态优先 / 复用优先**：纯函数工具用 `final class + private ctor + static`；写新零件前先确认本计划没在别处定义过。

---

## 模块与命令速查

| 模块 | 路径 | 单测命令（在 server 根目录跑） |
|---|---|---|
| sanyan-common-util | `foundation_packages/sanyan-common-util` | `mvn -q -pl foundation_packages/sanyan-common-util test` |
| sanyan-llm-core | `business_packages/sanyan-llm-core` | `mvn -q -pl business_packages/sanyan-llm-core test` |
| sanyan-chat-core | `business_packages/sanyan-chat-core` | `mvn -q -pl business_packages/sanyan-chat-core test` |
| sanyan-memory-core | `business_packages/sanyan-memory-core` | `mvn -q -pl business_packages/sanyan-memory-core test` |
| sanyan-proactive-core | `business_packages/sanyan-proactive-core` | `mvn -q -pl business_packages/sanyan-proactive-core test` |

> 多模块联动编译用 `-am`（also make dependencies），如 Task 1 改了 llm 接口要带 `-am` 把 proactive/chat 一起编：
> `mvn -q -pl business_packages/sanyan-proactive-core -am test`

---

## File Structure（本批新增/改动的文件清单）

**新增（基础层，可复用）**
- `foundation_packages/sanyan-common-util/src/main/java/com/sanyan/common/util/SpokenChineseTime.java`
  —— 纯静态：`LocalDateTime` → 中文口语时刻 + 完整中文时间标签。从 chat-core `AiService` 抽出，主对话与主动推送共用。
- `foundation_packages/sanyan-common-util/src/main/java/com/sanyan/common/util/RelativeTime.java`
  —— 纯静态：两个 `Instant` 的间隔 → "刚刚 / 3天前 / 上周 / 约2周前 / 约3个月前"。

**改动**
- `business_packages/sanyan-llm-core/.../internal/LLMProvider.java` —— `chat` 加 `LlmTaskType` 形参（T1）
- `business_packages/sanyan-llm-core/.../internal/DeepSeekAdapter.java` —— USER_FACING 加解码参数（T1）
- `business_packages/sanyan-llm-core/.../internal/DoubaoAdapter.java` —— 实现新签名（T1）
- `business_packages/sanyan-llm-core/.../internal/LLMProviderRouter.java` —— 透传 taskType（T1）
- `business_packages/sanyan-chat-core/.../internal/AiService.java` —— 复用 `SpokenChineseTime`（T2）
- `business_packages/sanyan-proactive-core/.../internal/generator/ProactivePromptBuilder.java` —— 注入当前时间（T2）、称呼约束（T3）、反重复段（T5）
- `business_packages/sanyan-memory-core/.../internal/orchestrator/MemoryContextBuilder.java` —— RAG 片段渲染相对时间（T4）
- `business_packages/sanyan-chat-api/.../ChatApi.java` + `chat-core/.../api/ChatApiImpl.java` + `MessageRepository.java` —— 最近主动消息查询、未回应计数（T5/T6）
- `business_packages/sanyan-proactive-core/.../internal/generator/GenerateContext.java` —— 加 `recentProactiveMessages` 字段（T5）
- `business_packages/sanyan-proactive-core/.../internal/ProactiveDispatcher.java` —— 填充新字段（T5）
- `business_packages/sanyan-proactive-core/.../internal/FrequencyGate.java` —— 互动退避（T6/T8）
- `business_packages/sanyan-proactive-core/.../internal/ProactiveProperties.java` —— 退避阈值配置（T8）

---

## Task 1: LLM 解码参数反重复（P-T7）

**目标**：给 `LLMProvider.chat` 加 `LlmTaskType` 形参；`DeepSeekAdapter` 仅对 `USER_FACING` 在请求体里加 `temperature=0.9 / frequency_penalty=0.5 / presence_penalty=0.3`，后台任务（`BACKGROUND`，记忆抽取/摘要 JSON）保持原样不加参数（确定性优先）。

**为什么改接口而不是全局加参数**：`router.chat(taskType, msgs)` 有 taskType，但 `provider.chat(msgs)` 拿不到。若直接在 adapter 全局加 penalty，会污染后台 JSON 抽取/摘要，破坏结构化输出。把 taskType 透传到 adapter 是唯一干净的作用域隔离方式。

**Files:**
- Modify: `business_packages/sanyan-llm-core/src/main/java/com/sanyan/llm/internal/LLMProvider.java`
- Modify: `business_packages/sanyan-llm-core/src/main/java/com/sanyan/llm/internal/LLMProviderRouter.java:56-74`
- Modify: `business_packages/sanyan-llm-core/src/main/java/com/sanyan/llm/internal/DeepSeekAdapter.java:91-94`
- Modify: `business_packages/sanyan-llm-core/src/main/java/com/sanyan/llm/internal/DoubaoAdapter.java:101-104`
- Test: `business_packages/sanyan-llm-core/src/test/java/com/sanyan/llm/internal/DeepSeekAdapterTest.java`（若不存在则新建）

- [ ] **Step 1: 写失败测试 — DeepSeek 请求体按 taskType 加/不加解码参数**

用 `MockRestServiceServer` 拦截出站请求，断言请求体 JSON。新建/追加到 `DeepSeekAdapterTest.java`：

```java
package com.sanyan.llm.internal;

import com.sanyan.llm.LlmTaskType;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.MediaType.APPLICATION_JSON;

class DeepSeekAdapterTest {

    private static final String OK_BODY = """
            {"choices":[{"message":{"role":"assistant","content":"嗨"}}]}""";

    private DeepSeekAdapter newAdapter(MockRestServiceServer[] serverOut) {
        DeepSeekAdapter adapter = new DeepSeekAdapter(
                "k", "https://api.deepseek.com", "deepseek-v4-flash",
                java.time.Duration.ofSeconds(3), java.time.Duration.ofSeconds(30));
        RestClient restClient = (RestClient) ReflectionTestUtils.getField(adapter, "restClient");
        // 用 bind 模式给已有 restClient 装 mock server
        serverOut[0] = MockRestServiceServer.bindTo(RestClient.builder(restClient)).build();
        return adapter;
    }

    @Test
    void userFacing_should_include_antiRepeat_decoding_params() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        DeepSeekAdapter adapter = newAdapter(holder);
        holder[0].expect(requestTo("https://api.deepseek.com/chat/completions"))
                .andExpect(jsonPath("$.frequency_penalty").value(0.5))
                .andExpect(jsonPath("$.presence_penalty").value(0.3))
                .andExpect(jsonPath("$.temperature").value(0.9))
                .andRespond(withSuccess(OK_BODY, APPLICATION_JSON));

        adapter.chat(LlmTaskType.USER_FACING,
                List.of(Map.of("role", "user", "content", "在吗")));
        holder[0].verify();
    }

    @Test
    void background_should_NOT_include_decoding_params() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        DeepSeekAdapter adapter = newAdapter(holder);
        holder[0].expect(requestTo("https://api.deepseek.com/chat/completions"))
                .andExpect(jsonPath("$.frequency_penalty").doesNotExist())
                .andExpect(jsonPath("$.presence_penalty").doesNotExist())
                .andExpect(jsonPath("$.temperature").doesNotExist())
                .andRespond(withSuccess(OK_BODY, APPLICATION_JSON));

        adapter.chat(LlmTaskType.BACKGROUND,
                List.of(Map.of("role", "user", "content", "{抽取}")));
        holder[0].verify();
    }
}
```

> 注：若 `MockRestServiceServer.bindTo(RestClient.builder(restClient))` 在本仓 Spring 版本下行不通，退化方案：把 `DeepSeekAdapter` 构造器或一个包级可见 setter 暴露 `RestClient`，测试直接传入 `RestClient.builder().requestFactory(...)` 绑定的 mock。实现子代理按实际 API 调通即可，**断言内容（三个参数 USER_FACING 有、BACKGROUND 无）不变**。

- [ ] **Step 2: 跑测试看失败**

Run: `mvn -q -pl business_packages/sanyan-llm-core test -Dtest=DeepSeekAdapterTest`
Expected: 编译失败（`chat(LlmTaskType, List)` 还不存在）或断言失败。

- [ ] **Step 3: 改 `LLMProvider` 接口签名**

`LLMProvider.java` 把 `chat` 改成带 taskType：

```java
String chat(LlmTaskType taskType, List<Map<String, String>> chatMessages);
```

（顶部已 `import com.sanyan.llm.LlmTaskType;`，保留。）

- [ ] **Step 4: 改 `LLMProviderRouter` 透传 taskType**

`LLMProviderRouter.java:73` 把 `return provider.chat(openAiMessages);` 改成：

```java
return provider.chat(taskType, openAiMessages);
```

- [ ] **Step 5: 改 `DeepSeekAdapter` — USER_FACING 加解码参数**

把 `DeepSeekAdapter.chat` 签名与 requestBody 构造改为（替换 91-94 行附近）：

```java
/** USER_FACING 反重复解码参数：抑制复读 / 句式重复（仅面向用户的生成，后台 JSON 任务不加）。 */
private static final double USER_FACING_TEMPERATURE = 0.9;
private static final double USER_FACING_FREQUENCY_PENALTY = 0.5;
private static final double USER_FACING_PRESENCE_PENALTY = 0.3;

@Override
public String chat(LlmTaskType taskType, List<Map<String, String>> chatMessages) {
    Map<String, Object> requestBody = buildRequestBody(taskType, chatMessages);
    // ... 其余 try/catch 完全不变 ...
}

private Map<String, Object> buildRequestBody(LlmTaskType taskType, List<Map<String, String>> chatMessages) {
    if (taskType == LlmTaskType.USER_FACING) {
        return Map.of(
                "model", model,
                "messages", chatMessages,
                "temperature", USER_FACING_TEMPERATURE,
                "frequency_penalty", USER_FACING_FREQUENCY_PENALTY,
                "presence_penalty", USER_FACING_PRESENCE_PENALTY);
    }
    return Map.of("model", model, "messages", chatMessages);
}
```

把 `USER_FACING_*` 三个常量放在类字段区。`chat` 方法体里把原来内联的 `Map.of("model",...,"messages",...)` 换成 `buildRequestBody(taskType, chatMessages)`，其余日志/异常处理不动。

- [ ] **Step 6: 改 `DoubaoAdapter` 实现新签名（不加参数，保持禁用态）**

`DoubaoAdapter.chat` 改签名为 `public String chat(LlmTaskType taskType, List<Map<String, String>> chatMessages)`，方法体不变（豆包默认禁用，无需加解码参数；taskType 暂不使用，加一行注释说明"豆包默认禁用，本期不接 USER_FACING，无需区分 taskType"）。

- [ ] **Step 7: 跑测试看通过**

Run: `mvn -q -pl business_packages/sanyan-llm-core test -Dtest=DeepSeekAdapterTest`
Expected: PASS（2/2）。

- [ ] **Step 8: 跑 llm-core 全模块 + 下游编译**

Run: `mvn -q -pl business_packages/sanyan-llm-core test` 然后 `mvn -q -pl business_packages/sanyan-chat-core,business_packages/sanyan-proactive-core,business_packages/sanyan-memory-core -am test-compile`
Expected: 全绿（接口改动不影响调用方：调用方走 `LlmApi.chat(taskType, ...)`，不直接调 `LLMProvider`；只有 router 调，已改）。

- [ ] **Step 9: 提交**

```bash
git add business_packages/sanyan-llm-core
git commit -m "feat(llm): USER_FACING 加反重复解码参数（temperature/frequency/presence penalty），后台任务不受影响"
```

---

## Task 2: 主动推送 prompt 注入当前时间（P-T1）

**目标**：(a) 把 chat-core `AiService` 里的中文口语时刻逻辑抽到基础层 `SpokenChineseTime`，`AiService` 改为复用（消除重复、行为不变）；(b) `ProactivePromptBuilder` 在 system 段开头注入"[当前时间] yyyy年M月d日 E HH:mm（中文口语）"，让主动推送也有时间锚点。

**为什么抽基础层**：`AiService.toSpokenChineseTime` / `formatCurrentTime` 是现成可复用件，主动推送需要同样能力。按"复用优先"，下沉 `sanyan-common-util` 供两边共用，禁止在 proactive 复制一份。

**Files:**
- Create: `foundation_packages/sanyan-common-util/src/main/java/com/sanyan/common/util/SpokenChineseTime.java`
- Test: `foundation_packages/sanyan-common-util/src/test/java/com/sanyan/common/util/SpokenChineseTimeTest.java`
- Modify: `business_packages/sanyan-chat-core/src/main/java/com/sanyan/chat/internal/AiService.java:165-214`（删私有时刻逻辑，改调 `SpokenChineseTime`）
- Modify: `business_packages/sanyan-proactive-core/src/main/java/com/sanyan/proactive/internal/generator/ProactivePromptBuilder.java`
- Test: `business_packages/sanyan-proactive-core/src/test/java/com/sanyan/proactive/internal/generator/ProactivePromptBuilderTest.java`

- [ ] **Step 1: 写失败测试 — `SpokenChineseTime`（基础层纯静态）**

```java
package com.sanyan.common.util;

import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.assertThat;

class SpokenChineseTimeTest {

    @Test
    void spoken_should_render_chinese_colloquial_time() {
        assertThat(SpokenChineseTime.spoken(LocalDateTime.of(2026, 6, 17, 1, 30))).isEqualTo("凌晨一点半");
        assertThat(SpokenChineseTime.spoken(LocalDateTime.of(2026, 6, 17, 14, 5))).isEqualTo("下午两点零五分");
        assertThat(SpokenChineseTime.spoken(LocalDateTime.of(2026, 6, 17, 12, 0))).isEqualTo("中午十二点整");
        assertThat(SpokenChineseTime.spoken(LocalDateTime.of(2026, 6, 17, 22, 30))).isEqualTo("晚上十点半");
    }

    @Test
    void label_should_render_full_chinese_datetime_with_spoken_suffix() {
        // 2026-06-17 是周三
        String label = SpokenChineseTime.label(LocalDateTime.of(2026, 6, 17, 22, 30));
        assertThat(label).contains("2026年6月17日");
        assertThat(label).contains("周三");
        assertThat(label).contains("22:30");
        assertThat(label).contains("（晚上十点半）");
    }
}
```

- [ ] **Step 2: 跑测试看失败**

Run: `mvn -q -pl foundation_packages/sanyan-common-util test -Dtest=SpokenChineseTimeTest`
Expected: 编译失败（`SpokenChineseTime` 不存在）。

- [ ] **Step 3: 实现 `SpokenChineseTime`（搬 `AiService` 逻辑，改成 `final class + private ctor + static`）**

```java
package com.sanyan.common.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;

/**
 * 中文口语时间格式化（纯静态，无状态）。
 *
 * <p>从 chat-core {@code AiService} 抽出下沉到基础层：主对话（{@code AiService}）与主动推送
 * （{@code ProactivePromptBuilder}）都需要把"当前时间"喂给 LLM，且要附中文口语版本减少
 * LLM 数字→自然语言时间的幻觉（实测 01:30 会被说成"两点多"）。
 */
public final class SpokenChineseTime {

    private SpokenChineseTime() {}

    /** 完整中文时间标签格式：yyyy年M月d日 E HH:mm（E=周几）。 */
    private static final DateTimeFormatter LABEL_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy年M月d日 E HH:mm", Locale.CHINESE);

    private static final String[] DIGITS =
            {"零", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十", "十一", "十二"};

    /**
     * 完整时间标签 + 口语后缀，如 {@code "2026年6月17日 周三 22:30（晚上十点半）"}。
     * 调用方通常拼成 {@code "[当前时间] " + label(now)}。
     */
    public static String label(LocalDateTime time) {
        Objects.requireNonNull(time, "time must not be null");
        return time.format(LABEL_FORMATTER) + "（" + spoken(time) + "）";
    }

    /** 仅口语时刻，如 {@code "凌晨一点半"} / {@code "下午两点零五分"} / {@code "中午十二点整"}。 */
    public static String spoken(LocalDateTime time) {
        Objects.requireNonNull(time, "time must not be null");
        int hour = time.getHour();
        int minute = time.getMinute();
        String period;
        int hour12;
        if (hour == 0) { period = "凌晨"; hour12 = 12; }
        else if (hour < 6) { period = "凌晨"; hour12 = hour; }
        else if (hour < 12) { period = "上午"; hour12 = hour; }
        else if (hour == 12) { period = "中午"; hour12 = 12; }
        else if (hour < 18) { period = "下午"; hour12 = hour - 12; }
        else { period = "晚上"; hour12 = hour - 12; }

        String hourStr = (hour12 == 2 ? "两" : chineseNumber(hour12)) + "点";
        String minuteStr;
        if (minute == 0) {
            minuteStr = "整";
        } else if (minute == 30) {
            minuteStr = "半";
        } else if (minute < 10) {
            minuteStr = "零" + chineseNumber(minute) + "分";
        } else {
            minuteStr = chineseDoubleDigitNumber(minute) + "分";
        }
        return period + hourStr + minuteStr;
    }

    private static String chineseNumber(int n) {
        if (n >= 0 && n <= 12) return DIGITS[n];
        return String.valueOf(n);
    }

    private static String chineseDoubleDigitNumber(int n) {
        if (n < 10) return DIGITS[n];
        if (n == 10) return "十";
        if (n < 20) return "十" + DIGITS[n - 10];
        int tens = n / 10;
        int ones = n % 10;
        return DIGITS[tens] + "十" + (ones == 0 ? "" : DIGITS[ones]);
    }
}
```

> 注：以上 `chineseDoubleDigitNumber` 把 `AiService` 里被截断未显示的实现补全（10→"十"，11-19→"十X"，20-59→"X十Y"）。实现子代理务必对照原 `AiService.chineseDoubleDigitNumber` 全文（`AiService.java:211` 起）核对行为一致，确保抽取无回归。

- [ ] **Step 4: 跑基础层测试看通过**

Run: `mvn -q -pl foundation_packages/sanyan-common-util test -Dtest=SpokenChineseTimeTest`
Expected: PASS（2/2）。

- [ ] **Step 5: 重构 `AiService` 复用 `SpokenChineseTime`（行为不变）**

`AiService.java`：删除私有方法 `toSpokenChineseTime` / `chineseNumber` / `chineseDoubleDigitNumber` 及 `DIGITS` 常量；`formatCurrentTime()` 改为：

```java
private String formatCurrentTime() {
    return SpokenChineseTime.label(LocalDateTime.now());
}
```

顶部加 `import com.sanyan.common.util.SpokenChineseTime;`。**注意** `assembleSystemPrompt` 仍是 `characterPrompt + "\n\n[当前时间] " + time + "\n\n" + TIME_AWARENESS_GUIDE;`——`time` 现在由 `SpokenChineseTime.label` 提供，等价于原 `formatted +"（"+toSpokenChineseTime+"）"`。

确认 `sanyan-chat-core/pom.xml` 已依赖 `sanyan-common-util`（基础模块业务方都依赖；若没有则加）。

- [ ] **Step 6: 跑 chat-core 测试确认 AiService 无回归**

Run: `mvn -q -pl business_packages/sanyan-chat-core test`
Expected: 全绿（尤其 `AiServiceTest` / `toSpokenChineseTime` 相关用例。若旧测试直接测 `AiService.toSpokenChineseTime`，把它迁移成测 `SpokenChineseTime.spoken` 或删除——逻辑已被 `SpokenChineseTimeTest` 覆盖）。

- [ ] **Step 7: 写失败测试 — `ProactivePromptBuilder` 注入当前时间**

`ProactivePromptBuilderTest.java` 追加（注意：builder 将获得注入的 `Clock`，构造方式见 Step 8）：

```java
@Test
void build_should_inject_current_time_into_system() {
    // 固定时钟：2026-06-17 22:30 (UTC+8)，周三晚上
    java.time.Clock fixed = java.time.Clock.fixed(
            java.time.Instant.parse("2026-06-17T14:30:00Z"), java.time.ZoneId.of("Asia/Shanghai"));
    ProactivePromptBuilder b = new ProactivePromptBuilder(fixed);

    List<ChatMessage> messages = b.build(ctx("", null, Map.of()), "说句话。");
    String system = messages.get(0).content();
    assertThat(system).contains("[当前时间]");
    assertThat(system).contains("2026年6月17日");
    assertThat(system).contains("晚上十点半");
}
```

同时把测试类里现有的 `private final ProactivePromptBuilder builder = new ProactivePromptBuilder();` 改成带 Clock 的构造（用系统时钟即可，因为旧用例不校验时间）：
`private final ProactivePromptBuilder builder = new ProactivePromptBuilder(java.time.Clock.systemDefaultZone());`

- [ ] **Step 8: 跑测试看失败**

Run: `mvn -q -pl business_packages/sanyan-proactive-core test -Dtest=ProactivePromptBuilderTest`
Expected: 编译失败（`ProactivePromptBuilder(Clock)` 构造器不存在）。

- [ ] **Step 9: 改 `ProactivePromptBuilder` 注入 Clock + 当前时间段**

```java
import com.sanyan.common.util.SpokenChineseTime;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.Clock;
import java.time.LocalDateTime;

@Component
public class ProactivePromptBuilder {

    static final String PERSONA_BASE = /* 原文不变 */ ;
    static final String MEMORY_PREFIX = "她记得关于你的事：\n";
    static final String CURRENT_TIME_PREFIX = "[当前时间] ";

    private final Clock clock;

    @Autowired
    public ProactivePromptBuilder(Clock clock) {
        this.clock = clock;
    }

    public List<ChatMessage> build(GenerateContext ctx, String sceneInstruction) {
        StringBuilder system = new StringBuilder(PERSONA_BASE);
        system.append("\n\n").append(CURRENT_TIME_PREFIX)
              .append(SpokenChineseTime.label(LocalDateTime.now(clock)));

        if (ctx.stagePromptSegment() != null && !ctx.stagePromptSegment().isBlank()) {
            system.append("\n\n").append(ctx.stagePromptSegment());
        }
        if (ctx.memoryContext() != null && !ctx.memoryContext().isEmpty()) {
            system.append("\n\n").append(MEMORY_PREFIX).append(ctx.memoryContext().text());
        }

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(system.toString()));
        messages.add(ChatMessage.user(sceneInstruction));
        return messages;
    }
}
```

> 需要一个 `Clock` Bean 可注入。先 grep 项目是否已有 `@Bean Clock`（`rg --color=never "Clock clock\(\)|@Bean.*Clock"`）。若无，在 `sanyan-proactive-core` 的 `ProactiveConfig` 加：
> ```java
> @Bean
> @ConditionalOnMissingBean(Clock.class)
> public Clock clock() { return Clock.system(java.time.ZoneId.of("Asia/Shanghai")); }
> ```
> 用 `@ConditionalOnMissingBean` 避免与其他模块的 Clock Bean 冲突。

- [ ] **Step 10: 跑测试看通过**

Run: `mvn -q -pl business_packages/sanyan-proactive-core test -Dtest=ProactivePromptBuilderTest`
Expected: PASS（含新用例 + 原有 2 个用例）。

- [ ] **Step 11: 跑两个改动模块全测**

Run: `mvn -q -pl foundation_packages/sanyan-common-util,business_packages/sanyan-chat-core,business_packages/sanyan-proactive-core -am test`
Expected: 全绿。

- [ ] **Step 12: 提交**

```bash
git add foundation_packages/sanyan-common-util business_packages/sanyan-chat-core business_packages/sanyan-proactive-core
git commit -m "feat(proactive): 主动推送 prompt 注入当前时间；中文口语时刻逻辑下沉 sanyan-common-util 与主对话共用"
```

---

## Task 3: 称呼频率约束（P-T6）

**目标**：在 `ProactivePromptBuilder.PERSONA_BASE` 里加一句称呼约束，禁止 LLM 几乎每句都加"笨蛋"等称呼开头。纯 prompt 改动。

**Files:**
- Modify: `business_packages/sanyan-proactive-core/src/main/java/com/sanyan/proactive/internal/generator/ProactivePromptBuilder.java`（`PERSONA_BASE` 常量）
- Test: `business_packages/sanyan-proactive-core/src/test/java/com/sanyan/proactive/internal/generator/ProactivePromptBuilderTest.java`

- [ ] **Step 1: 写失败测试 — system 段含称呼约束**

```java
@Test
void persona_should_constrain_nickname_frequency() {
    List<ChatMessage> messages = builder.build(ctx("", null, Map.of()), "说句话。");
    String system = messages.get(0).content();
    // 约束关键词：大多数消息不要以称呼开头
    assertThat(system).contains("称呼");
    assertThat(system).contains("不要每");
}
```

- [ ] **Step 2: 跑测试看失败**

Run: `mvn -q -pl business_packages/sanyan-proactive-core test -Dtest=ProactivePromptBuilderTest#persona_should_constrain_nickname_frequency`
Expected: FAIL（断言找不到"称呼"/"不要每"）。

- [ ] **Step 3: 改 `PERSONA_BASE` 加称呼约束**

把 `PERSONA_BASE` 改为（在原文末尾追加一句约束）：

```java
static final String PERSONA_BASE =
        "你是小婉，用户的 AI 伴侣。下面是你主动找用户聊天的场景——按你的人设和当前关系语气，自然地开口，"
                + "像真人发消息一样口语、简短，不要像客服或机器人。"
                + "称呼（如昵称、\"笨蛋\"之类）偶尔点缀即可，大多数消息不要以称呼开头，"
                + "正常人不会每句话都喊对方称呼。";
```

- [ ] **Step 4: 跑测试看通过**

Run: `mvn -q -pl business_packages/sanyan-proactive-core test -Dtest=ProactivePromptBuilderTest`
Expected: PASS（全部用例）。

- [ ] **Step 5: 提交**

```bash
git add business_packages/sanyan-proactive-core
git commit -m "feat(proactive): 人设加称呼频率约束，避免每句都加'笨蛋'等称呼开头"
```

---

## Task 4: 记忆注入带相对时间（P-T2）

**目标**：(a) 基础层新增 `RelativeTime`，把两个 `Instant` 的间隔渲染成"刚刚 / N天前 / 上周 / 约N周前 / 约N个月前"；(b) `MemoryContextBuilder` 给 RAG 召回的每条片段加相对时间前缀（现在第 86-89 行只取 `chunkText`，把 `occurredAt` 丢了），让 LLM 知道"那段记忆多久以前"，不再把 25 天前的事说成今天。

**Files:**
- Create: `foundation_packages/sanyan-common-util/src/main/java/com/sanyan/common/util/RelativeTime.java`
- Test: `foundation_packages/sanyan-common-util/src/test/java/com/sanyan/common/util/RelativeTimeTest.java`
- Modify: `business_packages/sanyan-memory-core/src/main/java/com/sanyan/memory/internal/orchestrator/MemoryContextBuilder.java:55,65,83-90`
- Test: `business_packages/sanyan-memory-core/src/test/java/com/sanyan/memory/internal/orchestrator/MemoryContextBuilderTest.java`（若存在则追加，否则新建）

- [ ] **Step 1: 写失败测试 — `RelativeTime.describe`**

```java
package com.sanyan.common.util;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import static org.assertj.core.api.Assertions.assertThat;

class RelativeTimeTest {

    private static final Instant NOW = Instant.parse("2026-06-17T12:00:00Z");

    @Test
    void describe_buckets() {
        assertThat(RelativeTime.describe(NOW.minus(30, ChronoUnit.MINUTES), NOW)).isEqualTo("刚刚");
        assertThat(RelativeTime.describe(NOW.minus(5, ChronoUnit.HOURS), NOW)).isEqualTo("今天");
        assertThat(RelativeTime.describe(NOW.minus(1, ChronoUnit.DAYS), NOW)).isEqualTo("昨天");
        assertThat(RelativeTime.describe(NOW.minus(3, ChronoUnit.DAYS), NOW)).isEqualTo("3天前");
        assertThat(RelativeTime.describe(NOW.minus(8, ChronoUnit.DAYS), NOW)).isEqualTo("约1周前");
        assertThat(RelativeTime.describe(NOW.minus(25, ChronoUnit.DAYS), NOW)).isEqualTo("约3周前");
        assertThat(RelativeTime.describe(NOW.minus(60, ChronoUnit.DAYS), NOW)).isEqualTo("约2个月前");
    }

    @Test
    void describe_future_or_null_is_safe() {
        assertThat(RelativeTime.describe(NOW.plus(1, ChronoUnit.DAYS), NOW)).isEqualTo("刚刚");
        assertThat(RelativeTime.describe(null, NOW)).isEqualTo("");
    }
}
```

- [ ] **Step 2: 跑测试看失败**

Run: `mvn -q -pl foundation_packages/sanyan-common-util test -Dtest=RelativeTimeTest`
Expected: 编译失败（`RelativeTime` 不存在）。

- [ ] **Step 3: 实现 `RelativeTime`**

```java
package com.sanyan.common.util;

import java.time.Duration;
import java.time.Instant;

/**
 * 把过去某时刻相对"现在"的间隔渲染成中文口语相对时间（纯静态，无状态）。
 *
 * <p>用于把记忆片段的发生时间喂给 LLM 时附"多久以前"，避免 LLM 把陈旧记忆当成"今天/刚才"。
 * 分桶：&lt;1h 刚刚 / 当天 今天 / 1天 昨天 / &lt;7天 N天前 / &lt;30天 约N周前 / 否则 约N个月前。
 */
public final class RelativeTime {

    private RelativeTime() {}

    /**
     * @param past 过去时刻（null → 返回空串，调用方据此跳过时间前缀）
     * @param now  当前时刻
     * @return 中文相对时间；past 在未来或不足 1 小时一律 "刚刚"
     */
    public static String describe(Instant past, Instant now) {
        if (past == null || now == null) {
            return "";
        }
        long minutes = Duration.between(past, now).toMinutes();
        if (minutes < 60) {
            return "刚刚";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return "今天";
        }
        long days = hours / 24;
        if (days == 1) {
            return "昨天";
        }
        if (days < 7) {
            return days + "天前";
        }
        if (days < 30) {
            long weeks = days / 7;
            return "约" + weeks + "周前";
        }
        long months = days / 30;
        return "约" + months + "个月前";
    }
}
```

> 校验：25 天 → days=25 → `<30` → weeks=25/7=3 → "约3周前" ✓；60 天 → months=2 → "约2个月前" ✓。

- [ ] **Step 4: 跑测试看通过**

Run: `mvn -q -pl foundation_packages/sanyan-common-util test -Dtest=RelativeTimeTest`
Expected: PASS（2/2）。

- [ ] **Step 5: 写失败测试 — `MemoryContextBuilder` RAG 片段带相对时间**

先看现有 `MemoryContextBuilderTest`（若有）的 mock 风格（mock `summaryRepository` / `profileRepository` / `ragSearchService`）。追加用例：

```java
@Test
void rag_fragments_should_be_prefixed_with_relative_time() {
    Long userId = 1L, characterId = 1L;
    // profile / summary 空，只给 RAG 一条 25 天前的片段
    when(profileRepository.findByUserIdAndCharacterId(userId, characterId)).thenReturn(Optional.empty());
    when(summaryRepository.findFirstByUserIdAndCharacterIdOrderByCreatedAtDesc(userId, characterId))
            .thenReturn(Optional.empty());
    Instant occurred = Instant.parse("2026-05-23T12:00:00Z"); // 距 fixed now 25 天
    when(ragSearchService.search(eq(userId), eq(characterId), any()))
            .thenReturn(List.of(new MemoryFragment("上次去吃了寿司", occurred, 0.9)));

    // 注入固定时钟 now=2026-06-17
    Clock fixed = Clock.fixed(Instant.parse("2026-06-17T12:00:00Z"), ZoneId.of("Asia/Shanghai"));
    ReflectionTestUtils.setField(builder, "clock", fixed);

    MemoryContext ctx = builder.build(userId, characterId, "吃的");
    assertThat(ctx).isNotNull();
    assertThat(ctx.text()).contains("约3周前");
    assertThat(ctx.text()).contains("上次去吃了寿司");
}
```

- [ ] **Step 6: 跑测试看失败**

Run: `mvn -q -pl business_packages/sanyan-memory-core test -Dtest=MemoryContextBuilderTest#rag_fragments_should_be_prefixed_with_relative_time`
Expected: FAIL（输出无"约3周前"前缀）或 `clock` 字段不存在。

- [ ] **Step 7: 改 `MemoryContextBuilder` 加 Clock + 渲染相对时间**

加字段与 import：

```java
import com.sanyan.common.util.RelativeTime;
import java.time.Clock;
import java.time.Instant;
```

字段区加（与 FrequencyGate 同风格，便于 `ReflectionTestUtils.setField`）：

```java
/** 可测时钟；默认系统时区。测试经 ReflectionTestUtils 注入固定 Clock。 */
private Clock clock = Clock.systemDefaultZone();
```

把 83-90 行 RAG 段循环改为：

```java
List<MemoryFragment> fragments = ragSearchService.search(userId, characterId, queryText);
if (!fragments.isEmpty()) {
    sb.append(SECTION_RAG_TITLE).append("\n");
    Instant now = Instant.now(clock);
    for (MemoryFragment f : fragments) {
        String rel = RelativeTime.describe(f.occurredAt(), now);
        String prefix = rel.isEmpty() ? "" : "（" + rel + "）";
        // 把换行换成空格再列项，让 "- " 列表始终单行，避免污染 LLM 看到的结构
        sb.append("- ").append(prefix).append(f.chunkText().replace("\n", " ")).append("\n");
    }
}
```

确认 `sanyan-memory-core/pom.xml` 依赖 `sanyan-common-util`（应已依赖；若无则加）。

- [ ] **Step 8: 跑测试看通过**

Run: `mvn -q -pl business_packages/sanyan-memory-core test -Dtest=MemoryContextBuilderTest`
Expected: PASS（新用例 + 原有用例。原有用例若断言 RAG 行精确等于 `"- 文本"`，需同步更新为含时间前缀）。

- [ ] **Step 9: 跑两个改动模块全测**

Run: `mvn -q -pl foundation_packages/sanyan-common-util,business_packages/sanyan-memory-core -am test`
Expected: 全绿。

- [ ] **Step 10: 提交**

```bash
git add foundation_packages/sanyan-common-util business_packages/sanyan-memory-core
git commit -m "feat(memory): RAG 记忆片段注入相对时间前缀（约N周前等），避免 LLM 把旧事当今天"
```

---

## Task 5: prompt 注入"最近推过啥" + 反重复指令（P-T5）

**目标**：把"最近已主动发过的 N 条消息"喂回主动推送 prompt，并明确指令"别重复这些话题和句式"。数据来自 message 表 `is_proactive=true` 的 AI 消息（V13 已建列，见 `def5ba3`，本批与之一起部署）。

**链路**：新增 `MessageRepository` 查询 → `ChatApi.listRecentProactive(userId, limit)` → `ProactiveDispatcher.deliver` 取数填进 `GenerateContext.recentProactiveMessages` → `ProactivePromptBuilder.build` 拼"最近你已经主动发过"段 + 反重复指令。

**Files:**
- Modify: `business_packages/sanyan-chat-core/src/main/java/com/sanyan/chat/internal/MessageRepository.java`
- Modify: `business_packages/sanyan-chat-api/src/main/java/com/sanyan/chat/ChatApi.java`
- Modify: `business_packages/sanyan-chat-core/src/main/java/com/sanyan/chat/api/ChatApiImpl.java`
- Test: `business_packages/sanyan-chat-core/src/test/java/com/sanyan/chat/api/ChatApiImplTest.java`（追加）
- Modify: `business_packages/sanyan-proactive-core/src/main/java/com/sanyan/proactive/internal/generator/GenerateContext.java`
- Modify: `business_packages/sanyan-proactive-core/src/main/java/com/sanyan/proactive/internal/ProactiveDispatcher.java:110-113`
- Modify: `business_packages/sanyan-proactive-core/src/main/java/com/sanyan/proactive/internal/generator/ProactivePromptBuilder.java`
- Test: `business_packages/sanyan-proactive-core/.../ProactivePromptBuilderTest.java` + `ProactiveDispatcherTest.java`

- [ ] **Step 1: 写失败测试 — `MessageRepository` 查最近主动消息（@DataJpaTest）**

在 chat-core 现有 repo IT 风格下追加（先确认 `MessageRepositoryIT` 是否存在，没有则建）：

```java
@Test
void findRecentProactive_returns_only_proactive_ai_messages_desc() {
    // 准备：1 条 user、1 条普通 ai(isProactive=false)、2 条主动 ai(isProactive=true)
    persistMessage(1L, SenderType.USER, "在吗", false);
    persistMessage(1L, SenderType.AI, "在的", false);
    persistMessage(1L, SenderType.AI, "早安", true);
    persistMessage(1L, SenderType.AI, "睡了吗", true);

    List<MessageEntity> recent = repository
            .findByUserIdAndIsProactiveTrueOrderByIdDesc(1L, PageRequest.of(0, 10));

    assertThat(recent).hasSize(2);
    assertThat(recent.get(0).getContent()).isEqualTo("睡了吗"); // id 降序，最新在前
    assertThat(recent).allMatch(MessageEntity::isProactive);
}
```

- [ ] **Step 2: 跑看失败** — `mvn -q -pl business_packages/sanyan-chat-core test -Dtest=MessageRepositoryIT`；Expected: 方法不存在编译失败。

- [ ] **Step 3: 加 `MessageRepository` 查询方法**

```java
List<MessageEntity> findByUserIdAndIsProactiveTrueOrderByIdDesc(Long userId, Pageable pageable);
```

- [ ] **Step 4: 跑看通过** — 同 Step 2 命令；Expected: PASS。

- [ ] **Step 5: 写失败测试 — `ChatApiImpl.listRecentProactive`**

`ChatApiImplTest.java` 追加（mock `MessageRepository`）：

```java
@Test
void listRecentProactive_maps_to_dto_and_passes_pageable() {
    MessageEntity e = new MessageEntity();
    e.setId(9L); e.setUserId(1L); e.setSenderType(SenderType.AI);
    e.setContent("早安"); e.setProactive(true);
    when(messageRepository.findByUserIdAndIsProactiveTrueOrderByIdDesc(eq(1L), any()))
            .thenReturn(List.of(e));

    List<MessageDto> dtos = chatApi.listRecentProactive(1L, 5);
    assertThat(dtos).hasSize(1);
    assertThat(dtos.get(0).content()).isEqualTo("早安");
}
```

- [ ] **Step 6: 跑看失败** — `mvn -q -pl business_packages/sanyan-chat-core test -Dtest=ChatApiImplTest`；Expected: 方法不存在。

- [ ] **Step 7: 加 `ChatApi.listRecentProactive` 契约 + 实现**

`ChatApi.java` 接口加：

```java
/**
 * 查某用户最近 N 条 AI 主动推送消息（is_proactive=true），按 id 降序（最新在前）。
 * 供主动推送生成时做"反重复"——把最近推过的内容喂回 prompt，让 LLM 别复读。
 * @param limit 上限条数（必须 &gt; 0）
 */
List<MessageDto> listRecentProactive(Long userId, int limit);
```

`ChatApiImpl.java` 实现（复用现有 entity→dto 映射；若已有私有 `toDto`，直接用）：

```java
@Override
public List<MessageDto> listRecentProactive(Long userId, int limit) {
    return messageRepository
            .findByUserIdAndIsProactiveTrueOrderByIdDesc(userId, PageRequest.of(0, limit))
            .stream()
            .map(this::toDto)   // 与 listRecentByUser 共用同一映射；无则照其写法新增
            .toList();
}
```

- [ ] **Step 8: 跑看通过** — `mvn -q -pl business_packages/sanyan-chat-core test`；Expected: 全绿。

- [ ] **Step 9: 提交 chat 侧**

```bash
git add business_packages/sanyan-chat-api business_packages/sanyan-chat-core
git commit -m "feat(chat): ChatApi 暴露最近主动消息查询 listRecentProactive，供主动推送反重复"
```

- [ ] **Step 10: 写失败测试 — `ProactivePromptBuilder` 拼反重复段**

`GenerateContext` 将新增 `List<String> recentProactiveMessages` 字段（Step 12）。先写 builder 测试：

```java
@Test
void build_should_inject_recent_proactive_and_antirepeat_when_present() {
    GenerateContext c = new GenerateContext(1L, 1L,
            new RelationshipDto(1L, 1L, 250, 1, "朋友", 300, 0.5),
            "", null, Map.of(),
            List.of("早安呀", "睡了吗笨蛋"));   // 新字段：最近推过的
    List<ChatMessage> messages = builder.build(c, "再发一句。");
    String system = messages.get(0).content();
    assertThat(system).contains("最近你已经主动发过");
    assertThat(system).contains("早安呀");
    assertThat(system).contains("睡了吗笨蛋");
    assertThat(system).contains("不要重复");
}

@Test
void build_should_skip_antirepeat_when_recent_empty() {
    GenerateContext c = new GenerateContext(1L, 1L,
            new RelationshipDto(1L, 1L, 250, 1, "朋友", 300, 0.5),
            "", null, Map.of(), List.of());
    String system = builder.build(c, "说句话。").get(0).content();
    assertThat(system).doesNotContain("最近你已经主动发过");
}
```

并更新测试里 `ctx(...)` 帮手方法的 `GenerateContext` 构造，补最后一个 `List.of()` 参数。

- [ ] **Step 11: 跑看失败** — `mvn -q -pl business_packages/sanyan-proactive-core test -Dtest=ProactivePromptBuilderTest`；Expected: 编译失败（构造器参数不匹配）。

- [ ] **Step 12: `GenerateContext` 加字段**

```java
public record GenerateContext(
        Long userId,
        Long characterId,
        RelationshipDto relationship,
        String stagePromptSegment,
        MemoryContext memoryContext,
        Map<String, Object> payload,
        List<String> recentProactiveMessages) {}   // 最近已主动发过的消息文本（反重复用，可空 list）
```

更新 javadoc 加 `@param recentProactiveMessages`。

- [ ] **Step 13: `ProactivePromptBuilder` 拼反重复段**

在 `build()` 里 memory 段之后、组装 messages 之前加：

```java
static final String RECENT_PROACTIVE_PREFIX =
        "最近你已经主动发过这些（不要重复其中的话题和句式，换个角度或换件事说）：\n";

// ...build() 内：
List<String> recent = ctx.recentProactiveMessages();
if (recent != null && !recent.isEmpty()) {
    system.append("\n\n").append(RECENT_PROACTIVE_PREFIX);
    for (String m : recent) {
        system.append("- ").append(m.replace("\n", " ")).append("\n");
    }
}
```

- [ ] **Step 14: 跑看通过** — `mvn -q -pl business_packages/sanyan-proactive-core test -Dtest=ProactivePromptBuilderTest`；Expected: PASS。

- [ ] **Step 15: 写失败测试 — `ProactiveDispatcher` 填充 recentProactiveMessages**

`ProactiveDispatcherTest.java`：现有用例已 mock `chatApi` / `memoryApi` 等。加断言：dispatch 时调用 `chatApi.listRecentProactive(userId, N)`，且把结果文本传进 generator 的 ctx。最直接：用 `ArgumentCaptor<GenerateContext>` 捕获传给 `generator.generate(ctx)` 的上下文，断言 `ctx.recentProactiveMessages()` 来自 chatApi 返回。

```java
@Test
void deliver_should_feed_recent_proactive_into_context() {
    // given：mock chatApi.listRecentProactive 返回 1 条
    when(chatApi.listRecentProactive(eq(USER_ID), anyInt()))
            .thenReturn(List.of(new MessageDto(9L, USER_ID, "ai", "早安呀",
                    java.time.LocalDateTime.now())));
    // ...沿用现有 dispatch 成功路径的其余 mock（relationship/gate.allow=true/generator）...

    dispatcher.dispatch(event);   // event = A_GREETING 成功路径

    ArgumentCaptor<GenerateContext> cap = ArgumentCaptor.forClass(GenerateContext.class);
    verify(generator).generate(cap.capture());
    assertThat(cap.getValue().recentProactiveMessages()).containsExactly("早安呀");
}
```

> 实现子代理：对照现有 `ProactiveDispatcherTest` 的成功路径用例补齐 mock（`characterApi.findOrCreateRelationship`、`frequencyGate.allow`→true、`generators.get(type)`→mock generator、`generator.generate`→某 segments、`chatApi.deliverProactiveMessage`）。`RECENT_PROACTIVE_LIMIT` 常量值见 Step 16。

- [ ] **Step 16: 跑看失败 + 改 `ProactiveDispatcher.deliver`**

`mvn -q -pl business_packages/sanyan-proactive-core test -Dtest=ProactiveDispatcherTest`；Expected: FAIL。

改 `deliver()`（110-113 行附近）：

```java
static final int RECENT_PROACTIVE_LIMIT = 5;

// deliver() 内，构造 GenerateContext 前：
Map<String, Object> payload = parsePayload(event.getPayload());
String stagePromptSegment = characterApi.getStagePromptSegment(userId, characterId);
MemoryContext memoryContext = memoryApi.getRelevantContext(userId, characterId, "");

List<String> recentProactive = chatApi.listRecentProactive(userId, RECENT_PROACTIVE_LIMIT)
        .stream().map(MessageDto::content).toList();

GenerateContext ctx = new GenerateContext(
        userId, characterId, relationship, stagePromptSegment, memoryContext, payload, recentProactive);
```

顶部加 `import com.sanyan.chat.dto.MessageDto;`。

- [ ] **Step 17: 跑看通过** — `mvn -q -pl business_packages/sanyan-proactive-core test`；Expected: 全绿（所有 generator 测试也要过——它们构造 `GenerateContext` 的地方都要补新参数。实现子代理需同步修 `GreetingGeneratorTest`/`RecallGeneratorTest`/`EmotionCareGeneratorTest`/`EventFollowupGeneratorTest` 里的 `GenerateContext` 构造）。

- [ ] **Step 18: 跑联动编译** — `mvn -q -pl business_packages/sanyan-proactive-core -am test`；Expected: 全绿。

- [ ] **Step 19: 提交 proactive 侧**

```bash
git add business_packages/sanyan-proactive-core
git commit -m "feat(proactive): 主动推送 prompt 注入最近已推内容+反重复指令，压制复读"
```

---

## Task 6: 对空推送收敛/降频（P-T8）

**目标**：用户连续不理 AI 的主动推送时，自动停早晚安（`A_GREETING`），直到用户回话。从 message 表派生"自用户最后一条消息以来，AI 已发了几条主动消息"，超阈值则在 `FrequencyGate` 拦截 `A_GREETING`（放行 `B_RECALL`——召回本身是有意的再触达，且自带阶梯去重不会无限发）。用户一回话，计数自然归零。

**为什么放行 RECALL**：`RecallTrigger` 已有 24/72/168h 三档 + 每档每用户去重（TTL 8d），失联召回总共最多 3 条后自停，不是"狂推"源头；狂推源头是早晚安每天雷打不动 2 条。退避只需掐早晚安。

**Files:**
- Modify: `business_packages/sanyan-chat-core/src/main/java/com/sanyan/chat/internal/MessageRepository.java`
- Modify: `business_packages/sanyan-chat-api/src/main/java/com/sanyan/chat/ChatApi.java`
- Modify: `business_packages/sanyan-chat-core/src/main/java/com/sanyan/chat/api/ChatApiImpl.java`
- Test: `ChatApiImplTest.java` + `MessageRepositoryIT`
- Modify: `business_packages/sanyan-proactive-core/src/main/java/com/sanyan/proactive/internal/ProactiveProperties.java`
- Modify: `business_packages/sanyan-proactive-core/src/main/java/com/sanyan/proactive/internal/FrequencyGate.java`
- Test: `FrequencyGateTest.java`

- [ ] **Step 1: 写失败测试 — `MessageRepository` 计未回应主动消息数（@DataJpaTest）**

"未回应数" = 自该用户最后一条 user 消息之后的主动 AI 消息条数。若用户从未发过 user 消息，则统计全部主动消息。用一个查询实现：

```java
@Test
void countProactiveSinceLastUserMessage() {
    persistMessage(1L, SenderType.AI, "早安1", true);   // id1：用户首条消息前的主动消息也算未回应
    persistMessage(1L, SenderType.USER, "嗨", false);    // id2：用户最后回话点
    persistMessage(1L, SenderType.AI, "早安2", true);     // id3
    persistMessage(1L, SenderType.AI, "在吗", false);     // id4：非主动不计
    persistMessage(1L, SenderType.AI, "晚安2", true);     // id5

    long unanswered = repository.countUnansweredProactive(1L);
    assertThat(unanswered).isEqualTo(2); // id3 + id5
}

@Test
void countProactive_whenNoUserMessage_countsAll() {
    persistMessage(2L, SenderType.AI, "早安", true);
    persistMessage(2L, SenderType.AI, "晚安", true);
    assertThat(repository.countUnansweredProactive(2L)).isEqualTo(2);
}
```

- [ ] **Step 2: 跑看失败** — `mvn -q -pl business_packages/sanyan-chat-core test -Dtest=MessageRepositoryIT`；Expected: 方法不存在。

- [ ] **Step 3: 加 `MessageRepository.countUnansweredProactive`（@Query）**

```java
/**
 * 统计某用户"自最后一条 user 消息之后"的 AI 主动消息条数（未回应主动消息数）。
 * 若该用户从无 user 消息，COALESCE 兜底为 0，等于统计其全部主动消息。
 * 供主动推送互动退避：连续不回应则停早晚安。
 */
@Query("""
        select count(m) from MessageEntity m
        where m.userId = :userId and m.isProactive = true
          and m.id > coalesce(
              (select max(u.id) from MessageEntity u
                where u.userId = :userId and u.senderType = 'user'), 0)
        """)
long countUnansweredProactive(@Param("userId") Long userId);
```

顶部加 `import org.springframework.data.jpa.repository.Query;` `import org.springframework.data.repository.query.Param;`。

- [ ] **Step 4: 跑看通过** — 同 Step 2；Expected: PASS（2/2）。

- [ ] **Step 5: 写失败测试 + 实现 `ChatApi.countUnansweredProactive`**

`ChatApiImplTest` 追加 mock 用例（返回 repo 的 long），接口加：

```java
/** 该用户自最后一条消息以来未回应的主动推送条数。供主动推送互动退避降频。 */
long countUnansweredProactive(Long userId);
```

`ChatApiImpl`：

```java
@Override
public long countUnansweredProactive(Long userId) {
    return messageRepository.countUnansweredProactive(userId);
}
```

跑 `mvn -q -pl business_packages/sanyan-chat-core test`；先红后绿。提交：

```bash
git add business_packages/sanyan-chat-api business_packages/sanyan-chat-core
git commit -m "feat(chat): ChatApi 暴露 countUnansweredProactive，供主动推送互动退避"
```

- [ ] **Step 6: 写失败测试 — `FrequencyGate` 互动退避拦早晚安、放行召回**

`FrequencyGateTest.java` 追加（gate 将注入 `ChatApi`，见 Step 8）：

```java
@Test
void allow_should_block_greeting_when_unanswered_exceeds_threshold() {
    // 阈值默认 3；造 4 条未回应
    when(chatApi.countUnansweredProactive(USER_ID)).thenReturn(4L);
    // 其余三关全放行：非免打扰时段 + 场景开 + 未达每日上限（沿用现有放行 setup）
    assertThat(gate.allow(USER_ID, CHAR_ID, EventType.A_GREETING, STAGE_FRIEND)).isFalse();
}

@Test
void allow_should_still_permit_recall_when_unanswered_exceeds_threshold() {
    when(chatApi.countUnansweredProactive(USER_ID)).thenReturn(10L);
    assertThat(gate.allow(USER_ID, CHAR_ID, EventType.B_RECALL, STAGE_FRIEND)).isTrue();
}

@Test
void allow_should_permit_greeting_when_unanswered_below_threshold() {
    when(chatApi.countUnansweredProactive(USER_ID)).thenReturn(2L);
    assertThat(gate.allow(USER_ID, CHAR_ID, EventType.A_GREETING, STAGE_FRIEND)).isTrue();
}
```

> 实现子代理：沿用现有 `FrequencyGateTest` 让前三关放行的 setup（非免打扰 Clock、`props` 的 `scenesByStage`/`dailyCapByStage` fixture、`kvCache.get` 返回 null=0 已发）。新增 `chatApi` mock。

- [ ] **Step 7: 跑看失败** — `mvn -q -pl business_packages/sanyan-proactive-core test -Dtest=FrequencyGateTest`；Expected: FAIL/编译错（`chatApi` 字段 + 退避逻辑不存在）。

- [ ] **Step 8: `ProactiveProperties` 加退避阈值 + `FrequencyGate` 加退避关**

`ProactiveProperties.java` 加字段：

```java
/** 互动退避：自用户最后回话以来未回应的主动消息达到此数，停发 A_GREETING（放行 B_RECALL）。0 关闭退避。 */
private int unansweredGreetingBackoffThreshold = 3;
```

`FrequencyGate.java`：构造注入 `ChatApi`（`@RequiredArgsConstructor` 已在，加 `private final ChatApi chatApi;` 字段即可，Spring 自动注入跨模块 -api），在 `allow()` 三关之前加退避关：

```java
import com.sanyan.chat.ChatApi;

// allow() 内，最前面加：
if (type == EventType.A_GREETING && backoffActive(userId)) {
    log.info("门控拒绝（互动退避：连续未回应，暂停早晚安）: userId={}", userId);
    return false;
}

private boolean backoffActive(Long userId) {
    int threshold = props.getUnansweredGreetingBackoffThreshold();
    if (threshold <= 0) {
        return false;   // 配 0 关闭退避
    }
    try {
        return chatApi.countUnansweredProactive(userId) >= threshold;
    } catch (Exception e) {
        // 退避是降级优化，查询失败不应阻断正常门控——放行（fail-open）
        log.warn("互动退避查询失败，跳过退避关: userId={}, err={}", userId, e.getMessage());
        return false;
    }
}
```

> 注意 `FrequencyGate` 现在多依赖一个 `ChatApi`——确认 `sanyan-proactive-core/pom.xml` 已依赖 `sanyan-chat-api`（Dispatcher 已用 `ChatApi`，应已依赖）。

- [ ] **Step 9: 跑看通过** — `mvn -q -pl business_packages/sanyan-proactive-core test -Dtest=FrequencyGateTest`；Expected: PASS。
  - 同步修 `ProactiveDispatcherTest` 里构造 `FrequencyGate` 或 mock 它的地方（dispatcher 测试 mock 整个 `frequencyGate`，应不受影响；若有直接 new `FrequencyGate` 的测试需补 `chatApi` 参数）。

- [ ] **Step 10: 跑 proactive 全测 + 联动** — `mvn -q -pl business_packages/sanyan-proactive-core -am test`；Expected: 全绿。

- [ ] **Step 11: 提交**

```bash
git add business_packages/sanyan-proactive-core
git commit -m "feat(proactive): 互动退避——连续未回应则停早晚安（放行召回），治对空狂推"
```

---

## Final Gate（全部 task 完成后）

- [ ] **Step 1: 跑全量测试**

Run: `mvn -q test`（server 根目录，全模块）
Expected: 全绿。重点确认 `FlywayMigrationSyncTest` 仍绿（本批不加迁移，不应触动）。

- [ ] **Step 2: 派最终 code-reviewer 审整批 diff**

用 `pr-review-toolkit:code-reviewer` 审从本批第一个 commit 到 HEAD 的全部 diff（`git log --oneline` 找到 Task 1 之前的 HEAD = `96e2e87`，审 `96e2e87..HEAD`）。

- [ ] **Step 3: 停。等用户说"部署"**

不要自动部署。向用户汇报：6 个 task 完成、全量绿、未部署；提醒部署时会随 Spring Boot 启动跑 Flyway V13（is_proactive 列），P-T5/P-T8 依赖该列。

---

## 验收标准（部署后人工在 920 账号验证，非本计划自动化范围）

1. 主动消息不再把 N 天前的事说成"今天/刚才"（P-T1/P-T2）
2. 连续多条主动消息不再是同义复读（P-T5/P-T7）
3. 不再几乎每句"笨蛋"开头（P-T6）
4. 连续不回应 ≥3 条后早晚安自动暂停，回话后恢复（P-T8）

---

## 暂不在本批（第二批）

- P-T3：记忆抽取时注入当前日期→算绝对日期（治"永远后天"的数据层根因）
- P-T4：事件记忆状态机（发生后转向/淘汰，启用 `MemoryItemStatus.EXPIRED`）
- P-T9：主动推送接真实人设 base_prompt（替换 `PERSONA_BASE` 占位）
