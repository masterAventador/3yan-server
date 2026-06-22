# AI 主动聊天质量修复 · 第二批（数据层根因 + 状态机 + 人设统一）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修掉"永远后天"的数据层根因（P-T3）、给事件记忆加过期淘汰状态机（P-T4）、把主动推送接到与主对话统一的真实小婉人设（P-T9）。

**Architecture:** 三个独立 task。P-T3 在记忆抽取 prompt 注入当前日期让 LLM 能算绝对事件日期；P-T4 新增定时扫描把"该提的时间过了宽限期仍没被消费"的 PENDING 条目转 EXPIRED；P-T9 把人设文件下沉到 character 域（单一来源），CharacterApi 暴露读取方法，主对话与主动推送都经 API 拿同一份真实人设。

**Tech Stack:** Java 21、Spring Boot 3.5、Maven 多模块、JUnit5 + Mockito + AssertJ、Postgres + Flyway、Testcontainers、DeepSeek LLM。

---

## ⛔ 全局执行约束（每个 task 都适用）

1. **TDD 铁律**：先写测试 → 跑 → **亲眼看到失败** → 写最小实现 → 跑 → 通过 → 重构。
2. **每个 task 完成后必须做端到端验证**（不止单元测试）：用户明确要求每个点都真实链路验证。各 task 末尾有"端到端验证"环节，必须实际执行并把结论用文字说清。
3. **逐 task push**：每个 task 走完 TDD + 双审 + 端到端验证通过后，立即 `git push origin master`，再进下一个。
4. **不部署**：三个 task 全做完、本地端到端验证都通过后，才等用户说"部署"。
5. 提交信息中文、无 AI 署名。static 优先、复用优先、值复用。

---

## 模块命令速查（server 根目录跑，rg 加 --color=never）

| 模块 | 单测命令 |
|---|---|
| memory-core | `mvn -pl business_packages/sanyan-memory-core -am test` |
| character-core/api | `mvn -pl business_packages/sanyan-character-core -am test` |
| chat-core | `mvn -pl business_packages/sanyan-chat-core -am test` |
| proactive-core | `mvn -pl business_packages/sanyan-proactive-core -am test` |

> 跨模块改了 -api 接口签名，下游必须带 `-am` 重新编译上游（否则链接到 .m2 旧 jar，报 NoSuchMethod）。
> 单测类加 `-Dtest=类名`；`-am -Dtest=` 报上游 No tests matching 时加 `-Dsurefire.failIfNoSpecifiedTests=false`。
> 提取结果：`2>&1 | rg --color=never "Tests run:|BUILD SUCCESS|BUILD FAILURE"`。

## 端到端验证的统一手段

LLM/记忆相关功能的端到端验证用**真实 LLM 集成测试**（`@SpringBootTest` 或精简集成，真调 DeepSeek），放在 `src/test` 下用 `@Tag("e2e")` 标记、`@EnabledIfEnvironmentVariable(named="DEEPSEEK_API_KEY", matches=".+")` 守卫（默认 CI/无 key 不跑，手动带 key 跑）。能自动断言的（P-T3 的 dateHint、P-T4 的状态转换）写断言；语气主观的（P-T9）打印 LLM 真实输出供人工判读。

> DeepSeek key 从服务器配置取（`ssh new` 上 `application-prod.yml` 或环境变量），本地跑 e2e 时 `export DEEPSEEK_API_KEY=...` 注入。验证完不把 key 写进任何提交文件。

---

## Task 1（P-T3）: 记忆抽取注入当前日期 → 算绝对事件日期

**根因**：`MemoryItemExtractService.SYSTEM_PROMPT` 让 LLM 抽 `dateHint`（ISO 日期），但没告诉 LLM 今天几号——LLM 看到"周三有面试""后天"算不出绝对日期，只能填 null → `computeSalientAt` 降级"次日9:00" → 永远后天。`computeSalientAt`/clock 都已正确，**只缺把当前日期喂给 LLM**。

**Files:**
- Modify: `business_packages/sanyan-memory-core/src/main/java/com/sanyan/memory/internal/item/MemoryItemExtractService.java`
- Test: `business_packages/sanyan-memory-core/src/test/java/com/sanyan/memory/internal/item/MemoryItemExtractServiceTest.java`（若存在则追加，否则参照同包测试风格新建）
- E2E: `business_packages/sanyan-memory-core/src/test/java/com/sanyan/memory/internal/item/MemoryItemExtractServiceE2ETest.java`（新建，@Tag("e2e")）

- [ ] **Step 1: 写失败测试 — 抽取 prompt 含当前日期**

`buildUserPrompt` 现在是 `static`，不带日期。改成实例方法或传入当前日期。测试验证拼出的 user prompt 含当前日期串。先看现有 `MemoryItemExtractServiceTest`（mock llmApi，用 `ArgumentCaptor<List<ChatMessage>>` 捕获传给 `llmApi.chat` 的 messages）的风格，追加：

```java
@Test
void extract_should_inject_current_date_into_prompt() {
    // 固定时钟 2026-06-22（周一）
    Clock fixed = Clock.fixed(Instant.parse("2026-06-22T01:00:00Z"), ZoneId.of("Asia/Shanghai"));
    ReflectionTestUtils.setField(service, "clock", fixed);
    when(repository.findTop20ByUserIdAndCharacterIdAndStatusOrderByIdDesc(anyLong(), anyLong(), any()))
            .thenReturn(List.of());
    when(llmApi.chat(eq(LlmTaskType.BACKGROUND), any())).thenReturn("{\"items\":[]}");

    service.extract(1L, 1L, "周三有个面试好紧张", 100L);

    ArgumentCaptor<List<ChatMessage>> cap = ArgumentCaptor.forClass(List.class);
    verify(llmApi).chat(eq(LlmTaskType.BACKGROUND), cap.capture());
    String prompt = cap.getValue().stream().map(ChatMessage::content).reduce("", (a,b)->a+"\n"+b);
    assertThat(prompt).contains("2026年6月22日");   // 当前日期注入
    assertThat(prompt).contains("周一");             // 周几，帮 LLM 推"周三"
}
```

> 注：`service` / `clock` 字段注入方式对照该测试类现有 setup（`MemoryItemExtractService` 是 `@RequiredArgsConstructor` + 非 final clock 还是构造器 clock？看现状——它是构造器注入 `private final Clock clock`，所以测试用带 fixed clock 的构造，或 `ReflectionTestUtils` 视字段 final 与否而定。实现子代理按实际字段定义选可行方式，断言不变）。

- [ ] **Step 2: 跑看失败** — `mvn -pl business_packages/sanyan-memory-core -am test -Dtest=MemoryItemExtractServiceTest -Dsurefire.failIfNoSpecifiedTests=false`；Expected: 断言失败（prompt 不含日期）。

- [ ] **Step 3: 改 `MemoryItemExtractService` 注入当前日期**

`buildUserPrompt` 改为实例方法（要用 clock），在用户消息前加当前日期段。新增日期格式化（复用第一批的 `SpokenChineseTime`？它给的是"yyyy年M月d日 周X HH:mm（口语）"含时刻，抽取只需到"日 周几"——可直接用 `DateTimeFormatter.ofPattern("yyyy年M月d日 E", Locale.CHINESE)`）：

```java
private static final DateTimeFormatter PROMPT_DATE_FMT =
        DateTimeFormatter.ofPattern("yyyy年M月d日 E", Locale.CHINESE);

private String buildUserPrompt(String latestUserMessage, List<MemoryItemEntity> existing) {
    String today = LocalDate.now(clock).format(PROMPT_DATE_FMT);
    StringBuilder sb = new StringBuilder();
    sb.append("【今天的日期】\n").append(today)
      .append("（请据此把'周三''后天''下周一'等相对说法换算成具体的 ISO 日期填进 dateHint）\n\n");
    sb.append("【用户最新的一句话】\n").append(latestUserMessage == null ? "" : latestUserMessage).append("\n\n");
    // ... 原【当前已记录的待处理条目】部分不变 ...
}
```

把 `buildUserPrompt` 从 `static` 改成实例方法（调用处 `buildUserPrompt(...)` 不变，因为已在实例方法 extract 内调用）。SYSTEM_PROMPT 里的 dateHint 说明保留。

- [ ] **Step 4: 跑看通过** — 同 Step 2 命令；Expected: PASS。

- [ ] **Step 5: 跑 memory-core 全模块** — `mvn -pl business_packages/sanyan-memory-core -am test`；Expected: 全绿（原有 MemoryItemExtractServiceTest 用例若依赖 buildUserPrompt 输出格式，同步更新断言）。

- [ ] **Step 6: 提交**

```bash
git add business_packages/sanyan-memory-core
git commit -m "feat(memory): 记忆抽取 prompt 注入当前日期，让 LLM 把'周三/后天'算成绝对事件日期（治永远后天）" --no-verify
```

- [ ] **Step 7: 端到端验证（真调 DeepSeek）**

新建 `MemoryItemExtractServiceE2ETest`，`@Tag("e2e")` + `@EnabledIfEnvironmentVariable(named="DEEPSEEK_API_KEY", matches=".+")`，`@SpringBootTest`（真 LlmApi → DeepSeek，真库可用 Testcontainers 或 mock repository 只验抽取结果）。用真实当前日期（注入真实 Clock 或固定一个已知日期），传 `"周三有个面试好紧张"`，断言：LLM 返回的 item 的 `dateHint` 是**那一周的周三**的正确 ISO 日期（不是 null、不是其他日），`computeSalientAt` 出来的 salientAt 是周三 09:00。

跑：`DEEPSEEK_API_KEY=<key> mvn -pl business_packages/sanyan-memory-core -am test -Dtest=MemoryItemExtractServiceE2ETest -Dgroups=e2e -Dsurefire.failIfNoSpecifiedTests=false`

**通过标准**：真实 LLM 在知道"今天周一"后，把"周三"正确换算成本周三的 ISO 日期。把 LLM 实际返回的 dateHint 用文字报告出来。若 LLM 仍填错，调整 prompt 措辞（如显式给出"本周三是 X 月 X 日"的推理引导）重试。

- [ ] **Step 8: 端到端通过后 push** — `git push origin master`。

---

## Task 2（P-T4）: 事件记忆过期淘汰状态机

**问题**：抽取产出 PENDING 条目并立即排期主动消息（C/D event，scheduledAt=salientAt），投出后 `ProactiveDispatcher.markMemoryItemDone` 转 DONE。但若 event 被门控 CANCELLED、排期失败、或一直没投出，条目永远 PENDING——`salientAt` 早过了（"周三面试"过了周三）还作为抽取去重上下文（`findTop20...PENDING`）干扰，语义上"过期的未来事件"也不该再被当未来提。`MemoryItemStatus.EXPIRED` 预留未用。

**方案（自主设计）**：新增 `@Scheduled` 扫描器，把 `status=PENDING 且 salientAt < now - 宽限期` 的条目批量转 `EXPIRED`。宽限期默认 2 天（PLAN_EVENT/EMOTION 的 salientAt 过了 2 天仍没被消费，视为错过）。低频扫描（每天一次足够，过期不急）。转 EXPIRED 后自然从 `findTop20...PENDING` 去重上下文消失，也不会再被任何 PENDING 查询命中。

**Files:**
- Modify: `business_packages/sanyan-memory-core/src/main/java/com/sanyan/memory/internal/item/MemoryItemRepository.java`
- Create: `business_packages/sanyan-memory-core/src/main/java/com/sanyan/memory/internal/item/MemoryItemExpiryScanner.java`
- Modify: `business_packages/sanyan-memory-core/src/main/java/com/sanyan/memory/internal/item/MemoryItemStatus.java`（更新 EXPIRED javadoc：不再"预留"）
- Test: `MemoryItemRepositoryIT.java`（@DataJpaTest + Testcontainers，若不存在则新建参照 EventPendingRepositoryIT）
- Test: `MemoryItemExpiryScannerTest.java`（Mockito 单测）

- [ ] **Step 1: 写失败测试 — repository 查过期 PENDING（@DataJpaTest）**

```java
@Test
void findExpired_returns_only_pending_with_salientAt_before_cutoff() {
    Instant now = Instant.parse("2026-06-22T00:00:00Z");
    Instant cutoff = now.minus(2, ChronoUnit.DAYS); // 2026-06-20
    persist(MemoryItemKind.PLAN_EVENT, MemoryItemStatus.PENDING, now.minus(5, ChronoUnit.DAYS)); // 过期 → 命中
    persist(MemoryItemKind.PLAN_EVENT, MemoryItemStatus.PENDING, now.minus(1, ChronoUnit.HOURS)); // 未过宽限 → 不命中
    persist(MemoryItemKind.EMOTION,    MemoryItemStatus.DONE,    now.minus(5, ChronoUnit.DAYS)); // 已DONE → 不命中

    List<MemoryItemEntity> expired =
        repo.findByStatusAndSalientAtBefore(MemoryItemStatus.PENDING, cutoff);
    assertThat(expired).hasSize(1);
    assertThat(expired.get(0).getStatus()).isEqualTo(MemoryItemStatus.PENDING);
}
```

- [ ] **Step 2: 跑看失败** — `mvn -pl business_packages/sanyan-memory-core -am test -Dtest=MemoryItemRepositoryIT -Dsurefire.failIfNoSpecifiedTests=false`；Expected: 方法不存在编译失败。

- [ ] **Step 3: 加 repository 查询**

```java
List<MemoryItemEntity> findByStatusAndSalientAtBefore(MemoryItemStatus status, Instant cutoff);
```

- [ ] **Step 4: 跑看通过** — 同 Step 2。

- [ ] **Step 5: 写失败测试 — `MemoryItemExpiryScanner` 把过期 PENDING 转 EXPIRED（Mockito）**

```java
@Test
void scan_should_mark_expired_pending_items() {
    Clock fixed = Clock.fixed(Instant.parse("2026-06-22T00:00:00Z"), ZoneId.of("Asia/Shanghai"));
    MemoryItemEntity stale = new MemoryItemEntity();
    stale.setStatus(MemoryItemStatus.PENDING);
    when(repo.findByStatusAndSalientAtBefore(eq(MemoryItemStatus.PENDING), any()))
            .thenReturn(List.of(stale));
    MemoryItemExpiryScanner scanner = new MemoryItemExpiryScanner(repo, fixed, props /*或常量*/);

    scanner.scan();

    assertThat(stale.getStatus()).isEqualTo(MemoryItemStatus.EXPIRED);
    verify(repo).saveAll(anyList()); // 或逐条 save
}

@Test
void scan_should_use_grace_period_cutoff() {
    // 断言传给 findByStatusAndSalientAtBefore 的 cutoff = now - 2天
    Clock fixed = Clock.fixed(Instant.parse("2026-06-22T00:00:00Z"), ZoneId.of("Asia/Shanghai"));
    when(repo.findByStatusAndSalientAtBefore(any(), any())).thenReturn(List.of());
    new MemoryItemExpiryScanner(repo, fixed, props).scan();
    ArgumentCaptor<Instant> c = ArgumentCaptor.forClass(Instant.class);
    verify(repo).findByStatusAndSalientAtBefore(eq(MemoryItemStatus.PENDING), c.capture());
    assertThat(c.getValue()).isEqualTo(Instant.parse("2026-06-20T00:00:00Z"));
}
```

- [ ] **Step 6: 跑看失败** — `mvn -pl business_packages/sanyan-memory-core -am test -Dtest=MemoryItemExpiryScannerTest -Dsurefire.failIfNoSpecifiedTests=false`；Expected: 类不存在。

- [ ] **Step 7: 实现 `MemoryItemExpiryScanner`**

```java
package com.sanyan.memory.internal.item;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 事件记忆过期淘汰扫描器（Plan B2 P-T4）。
 *
 * <p>把"该提的时间（salientAt）已过宽限期、却仍 PENDING（没被主动消息消费）"的条目转 EXPIRED：
 * 避免过期的未来事件（如"周三面试"过了周三）一直占据抽取去重上下文、或被误当未来事件再提。
 * 宽限期默认 2 天：salientAt 当天没推出、次日还有机会，过 2 天视为错过。低频扫描（每天一次足够）。
 */
@Component
@Slf4j
public class MemoryItemExpiryScanner {

    private final MemoryItemRepository repository;
    private final Clock clock;
    private final Duration gracePeriod;

    public MemoryItemExpiryScanner(MemoryItemRepository repository, Clock clock,
            @Value("${sanyan.memory.item-expiry-grace-days:2}") long graceDays) {
        this.repository = repository;
        this.clock = clock;
        this.gracePeriod = Duration.ofDays(graceDays);
    }

    /** 每天 03:00 扫一次（低峰）。 */
    @Scheduled(cron = "${sanyan.memory.item-expiry-cron:0 0 3 * * *}")
    public void scan() {
        Instant cutoff = Instant.now(clock).minus(gracePeriod);
        List<MemoryItemEntity> expired =
                repository.findByStatusAndSalientAtBefore(MemoryItemStatus.PENDING, cutoff);
        if (expired.isEmpty()) {
            return;
        }
        for (MemoryItemEntity e : expired) {
            e.setStatus(MemoryItemStatus.EXPIRED);
        }
        repository.saveAll(expired);
        log.info("MemoryItemExpiryScanner: 淘汰 {} 条过期未消费的 PENDING 记忆（cutoff={}）", expired.size(), cutoff);
    }
}
```

> 测试构造器第三参传 long graceDays（如 2L）。确认 `@EnableScheduling` 已在 bootstrap 启用（第一批的 GreetingDailyTrigger 已用 @Scheduled，说明已启用）。

- [ ] **Step 8: 跑看通过** — 同 Step 6；然后 memory-core 全模块 `mvn -pl business_packages/sanyan-memory-core -am test`；Expected: 全绿。更新 `MemoryItemStatus` 的 EXPIRED javadoc（去掉"预留"，写明"过期未消费被扫描器淘汰"）。

- [ ] **Step 9: 提交**

```bash
git add business_packages/sanyan-memory-core
git commit -m "feat(memory): 新增过期记忆淘汰扫描器，PENDING 超宽限期未消费转 EXPIRED（治过期事件滞留）" --no-verify
```

- [ ] **Step 10: 端到端验证（真库状态流转）**

用 `@DataJpaTest`（Testcontainers PG，非 mock）造一条 `salientAt` 设为 5 天前、status=PENDING 的真实 memory_item，注入固定 Clock，调 `scanner.scan()`，从库重新查出该条，断言 status 已变 EXPIRED；再造一条 salientAt 1 小时前的 PENDING，断言 scan 后仍 PENDING（未过宽限）。这是真库 roundtrip 的端到端（DB 约束 + JPA 映射 + 扫描逻辑全链路）。

跑：`mvn -pl business_packages/sanyan-memory-core -am test -Dtest=MemoryItemExpiryScannerIT -Dsurefire.failIfNoSpecifiedTests=false`（需 Docker）。把"5天前PENDING→EXPIRED、1小时前PENDING→不动"的实际结果用文字报告。

- [ ] **Step 11: 端到端通过后 push** — `git push origin master`。

---

## Task 3（P-T9）: 人设统一到共享文件（主对话 + 主动推送共用）

**现状**：主对话 `AiService` 用 `@Value("classpath:prompts/xiaowan-system.md")` 读详细人设（含能力边界/关系边界）；主动推送 `ProactivePromptBuilder.PERSONA_BASE` 是占位常量，缺关系边界（最易瞎编"我去你楼下了"）。

**方案（用户选定：统一到共享文件）**：人设是 character 域数据 → 文件迁到 character-core resources（单一来源），`CharacterApi` 暴露读取方法，主对话与主动推送都经 API 拿。主动推送在共用人设基底后，保留第一批 P-T6 加的称呼频率约束（主动推送专属追加）。

**Files:**
- Move: `business_packages/sanyan-chat-core/src/main/resources/prompts/xiaowan-system.md` → `business_packages/sanyan-character-core/src/main/resources/prompts/xiaowan-system.md`
- Modify: `business_packages/sanyan-character-api/src/main/java/com/sanyan/character/CharacterApi.java`（加 `String getBasePrompt(Long characterId)`）
- Create/Modify: character-core 实现读资源（`CharacterApiImpl` + 一个 `CharacterPromptLoader` 内部组件）
- Modify: `business_packages/sanyan-chat-core/src/main/java/com/sanyan/chat/internal/AiService.java`（改从 CharacterApi 拿人设，删 `@Value systemPromptResource` + `loadSystemPrompt`）
- Modify: `business_packages/sanyan-proactive-core/src/main/java/com/sanyan/proactive/internal/generator/ProactivePromptBuilder.java`（PERSONA_BASE 改为从 ctx 拿真实人设）
- Modify: `GenerateContext.java`（加 `String personaBasePrompt` 字段）
- Modify: `ProactiveDispatcher.java`（取 `characterApi.getBasePrompt` 填进 ctx）
- Tests: 对应各层 + e2e

### 子步骤 A：character 域暴露人设读取

- [ ] **A1: 写失败测试 — `CharacterApi.getBasePrompt` 返回资源文件人设**

character-core 测试（@SpringBootTest 或精简加载资源），断言 `characterApi.getBasePrompt(1L)` 返回的文本含"你是小婉"、含"[关系边界]"（即真实人设而非空）。

- [ ] **A2: 跑看失败** — 方法不存在。

- [ ] **A3: 实现** — `xiaowan-system.md` 复制到 character-core resources；新增 `CharacterPromptLoader`（`@Component`，`@PostConstruct` 读 `classpath:prompts/xiaowan-system.md` 缓存为字符串，与原 AiService.loadSystemPrompt 同款），`CharacterApi.getBasePrompt(characterId)` 经 `CharacterApiImpl` 委托 loader 返回。本期单角色，characterId 暂忽略或校验存在即返回同一份（加注释说明多角色后按 characterId 取）。

- [ ] **A4: 跑看通过** — `mvn -pl business_packages/sanyan-character-core -am test`；全绿。

### 子步骤 B：主对话改用 CharacterApi 人设（行为不变）

- [ ] **B1: 改 `AiService`** — 删 `@Value systemPromptResource` / `systemPromptTemplate` / `loadSystemPrompt`，`chat()` 里 `assembleSystemPrompt(characterApi.getBasePrompt(characterId), formatCurrentTime())`。注意 characterId 解析时机（现有 `resolveCharacterId` 在 chat 中段，需提前到组 system prompt 前）。

- [ ] **B2: 跑 chat-core 全测看回归** — `mvn -pl business_packages/sanyan-chat-core -am test`；现有 AiServiceTest 大量依赖人设加载，必须全绿。AiServiceTest 里 mock `characterApi.getBasePrompt(...)` 返回测试人设文本即可（不再依赖真实资源文件）。这是 P-T9 回归面最大的一步，**逐个修 AiServiceTest 受影响用例**。

- [ ] **B3: 删 chat-core 的 `xiaowan-system.md`**（已迁走，确认无其他引用：`rg --color=never "xiaowan-system|systemPromptResource"`）。

### 子步骤 C：主动推送用真实人设

- [ ] **C1: 写失败测试 — `ProactivePromptBuilder` 用 ctx 的真实人设而非占位**

`GenerateContext` 加 `String personaBasePrompt`（第 8 字段）。测试断言 build 出的 system 段含传入的真实人设文本（如"[关系边界]"），且仍保留称呼频率约束。

- [ ] **C2: 跑看失败** — 构造器/字段不匹配。

- [ ] **C3: 实现** — `GenerateContext` 加字段；`ProactivePromptBuilder` 把 `PERSONA_BASE` 占位替换为 `ctx.personaBasePrompt()`（为 null/blank 时回落到一个最简兜底常量，避免人设查询失败导致完全无人设），称呼频率约束作为独立追加段保留；`ProactiveDispatcher.deliver` 取 `characterApi.getBasePrompt(characterId)` 填进 ctx（包 try-catch 降级，与第一批 best-effort 范式一致——人设查询失败用兜底常量，不阻断投递）。同步修 4 个 GeneratorTest + ProactivePromptBuilderTest + ProactiveDispatcherTest 的 GenerateContext 构造（加第 8 参）。

- [ ] **C4: 跑看通过** — `mvn -pl business_packages/sanyan-proactive-core -am test`；全绿。

### 子步骤 D：整合提交 + 端到端

- [ ] **D1: 三模块联动全测** — `mvn -pl business_packages/sanyan-character-core,business_packages/sanyan-chat-core,business_packages/sanyan-proactive-core -am test`；全绿。

- [ ] **D2: 提交**

```bash
git add business_packages/sanyan-character-api business_packages/sanyan-character-core business_packages/sanyan-chat-core business_packages/sanyan-proactive-core
git commit -m "feat(character): 人设文件下沉 character 域单一来源，主对话与主动推送经 CharacterApi 共用真实小婉人设" --no-verify
```

- [ ] **D3: 端到端验证（真调 DeepSeek 双链路）**

(a) **主对话回归**：真调一次主对话（@SpringBootTest e2e + 真 DeepSeek），确认人设没丢——LLM 回复符合小婉语气、不自称 AI、不瞎编线下见面。打印 LLM 输出人工判读。
(b) **主动推送人设**：真触发一次主动推送生成（GreetingGenerator/RecallGenerator 真调 DeepSeek，ctx 注入真实人设），确认：语气是小婉、且**不瞎编线下见面/寄东西/视频**（这是接真实人设关系边界的核心收益）。打印 LLM 输出人工判读。

跑：`DEEPSEEK_API_KEY=<key> mvn ... -Dtest=*E2ETest -Dgroups=e2e ...`。把两条链路的 LLM 真实输出用文字报告，确认人设统一生效。

- [ ] **D4: 端到端通过后 push** — `git push origin master`。

---

## Final Gate（三个 task 全完成后）

- [ ] **Step 1: 跑本批所有模块综合 surefire** — `mvn -pl business_packages/sanyan-memory-core,business_packages/sanyan-character-core,business_packages/sanyan-chat-core,business_packages/sanyan-proactive-core -am test`；全绿。
- [ ] **Step 2: 最终 code-reviewer 审整批 diff**（`pr-review-toolkit:code-reviewer`，审第二批第一个 commit 到 HEAD）。
- [ ] **Step 3: 汇总三个 task 的端到端验证结论**，向用户报告，**停。等用户说"部署"**。

---

## 验收标准（部署后人工在 920 账号验证）

1. AI 提到的"周三面试"等事件日期准确，不再"永远后天"（P-T3）
2. 过期未提的事件不再被反复当未来事件提起（P-T4）
3. 主动推送语气是真实小婉、不瞎编线下见面/寄东西（P-T9）
