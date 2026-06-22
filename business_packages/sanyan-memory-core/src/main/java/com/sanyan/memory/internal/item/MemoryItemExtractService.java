package com.sanyan.memory.internal.item;

import com.sanyan.llm.LlmApi;
import com.sanyan.llm.LlmTaskType;
import com.sanyan.llm.dto.ChatMessage;
import com.sanyan.memory.event.MemoryItemScheduledEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Plan 4：结构化记忆「实时抽取」核心服务（spec §4.2 / §4.3）。
 *
 * <p>由 {@link com.sanyan.memory.event.MemoryItemExtractListener} 在用户消息持久化后
 * 异步调用。流程：
 * <ol>
 *   <li>查该 (user, character) 现有 PENDING 条目作为去重上下文</li>
 *   <li>拼抽取 prompt（system 规则 + user：最新消息 + 现有条目清单），调
 *       {@link LlmApi#chat}（{@link LlmTaskType#BACKGROUND}，走 DeepSeek）</li>
 *   <li>{@link MemoryItemExtractResult#parse} 解析（容错降级空 items）</li>
 *   <li>按 action 落库：NEW 新建 / UPDATE 改已有 content / SKIP 不动</li>
 *   <li>对 NEW 的 PLAN_EVENT / EMOTION 发 {@link MemoryItemScheduledEvent} 让 proactive 排期；
 *       PROMISE 本期仅留存不发事件（spec §12）</li>
 * </ol>
 *
 * <p>LLM 调用模式照 {@code MemorySummaryService}：对话历史/条目清单塞进单条 user message
 * content，不展开成多个 turns（避免 LLM 接话而非执行指令）。
 *
 * <p>salient_at 计算（spec §4.2）：
 * <ul>
 *   <li>PLAN_EVENT：dateHint 解析出的事件日期当天 09:00；dateHint 缺失 / 非法时降级为次日 09:00</li>
 *   <li>EMOTION：观察日（now）次日 09:00</li>
 * </ul>
 * 时区用系统默认（单实例 dogfood 够用）；{@link Clock} 注入便于测试固定时间。
 *
 * <p><b>不加 {@code @Transactional}</b>：{@code extract} 内含数秒级的 {@code llmApi.chat}，
 * 若包在事务里会在 LLM 调用全程持有 DB 连接，高并发下耗尽连接池。参照同模块
 * {@code MemoryProfileRefreshService} 的做法——多条 {@code repository.save} 各自走默认事务，
 * 对独立的插入 / 更新语义可接受（抽取是后台异步任务，单条失败不影响其余条）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MemoryItemExtractService {

    /** 抽取规则 system prompt。硬约束：JSON-only 输出 + action/kind 枚举值大写。 */
    static final String SYSTEM_PROMPT = """
            你是用户长期记忆的抽取器。下面会给你「用户最新的一句话」和「该用户当前已记录但还没处理的记忆条目清单」。
            请你从最新这句话里抽取值得日后主动提起的「具体的、带时间或情绪的点」，并对照已有条目判定。

            只抽三类（kind 取大写）：
            - PLAN_EVENT：有明确时间的事（如"周三有面试"）
            - EMOTION：情绪状态（如"最近压力大、很焦虑"）
            - PROMISE：承诺（如"答应周末陪你看电影"）
            喜好 / 长期画像不要抽（那属于另一条画像通道）。

            对每条信息判定 action（大写）：
            - NEW：是一件已有条目里没有的新事 → 新建
            - UPDATE：是对某条已有条目的补充 / 更新 → 给出该条目的 targetId
            - SKIP：已有条目已经覆盖、无需变化 → 给出对应 targetId

            PLAN_EVENT 若能判断出事件日期，填 dateHint（ISO 日期，如 "2026-06-03"）；判断不出填 null。

            严格只输出 JSON（不要解释、不要 markdown 围栏）：
            {"items":[{"action":"NEW|UPDATE|SKIP","kind":"PLAN_EVENT|EMOTION|PROMISE","content":"...","dateHint":"YYYY-MM-DD 或 null","targetId":数字或 null}]}
            没有可抽取的内容时输出 {"items":[]}。
            """;

    /** 抽取 prompt 里注入的"今天日期"格式：到"日 + 周几"即可（抽取不需要时刻）。 */
    private static final DateTimeFormatter PROMPT_DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy年M月d日 E", Locale.CHINESE);

    private final LlmApi llmApi;
    private final MemoryItemRepository repository;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public void extract(Long userId, Long characterId, String latestUserMessage, Long sourceMessageId) {
        // 拉最近 20 条 PENDING 做去重上下文（已是该 user 数据，UPDATE 归属校验也复用它）
        List<MemoryItemEntity> existing =
                repository.findTop20ByUserIdAndCharacterIdAndStatusOrderByIdDesc(
                        userId, characterId, MemoryItemStatus.PENDING);

        String userPrompt = buildUserPrompt(latestUserMessage, existing);
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(SYSTEM_PROMPT));
        messages.add(ChatMessage.user(userPrompt));

        String raw = llmApi.chat(LlmTaskType.BACKGROUND, messages);
        MemoryItemExtractResult result = MemoryItemExtractResult.parse(raw);

        for (MemoryItemExtractResult.Item item : result.items()) {
            apply(userId, characterId, sourceMessageId, existing, item);
        }
    }

    private void apply(Long userId, Long characterId, Long sourceMessageId,
                       List<MemoryItemEntity> existing, MemoryItemExtractResult.Item item) {
        String action = item.action() == null ? "" : item.action().toUpperCase();
        switch (action) {
            case "NEW" -> handleNew(userId, characterId, sourceMessageId, item);
            case "UPDATE" -> handleUpdate(existing, item);
            default -> { /* SKIP 或未知 action：不动 */ }
        }
    }

    private void handleNew(Long userId, Long characterId, Long sourceMessageId,
                           MemoryItemExtractResult.Item item) {
        MemoryItemKind kind = parseKind(item.kind());
        if (kind == null) {
            return;
        }
        // content 是 memory_item.NOT NULL 列；LLM 可能对 NEW 返回 null/空，落库会抛约束异常 → 丢弃
        if (item.content() == null || item.content().isBlank()) {
            return;
        }
        Instant salientAt = computeSalientAt(kind, item.dateHint());

        MemoryItemEntity entity = new MemoryItemEntity();
        entity.setUserId(userId);
        entity.setCharacterId(characterId);
        entity.setKind(kind);
        entity.setContent(item.content());
        entity.setSalientAt(salientAt);
        entity.setStatus(MemoryItemStatus.PENDING);
        entity.setSourceMessageId(sourceMessageId);
        MemoryItemEntity saved = repository.save(entity);

        // 仅 PLAN_EVENT / EMOTION 排期主动消息；PROMISE 本期仅留存（spec §12）
        if (kind == MemoryItemKind.PLAN_EVENT || kind == MemoryItemKind.EMOTION) {
            events.publishEvent(new MemoryItemScheduledEvent(
                    saved.getId(), userId, characterId, kind.name(), salientAt));
        }
    }

    private void handleUpdate(List<MemoryItemEntity> existing, MemoryItemExtractResult.Item item) {
        if (item.targetId() == null) {
            return;
        }
        // 仅在已加载的本 user PENDING 列表里按 id 匹配，不用 repository.findById：
        // LLM 可能幻觉出不属于当前 user 的 targetId，findById 会越过归属边界改到别人的记忆
        // （数据隔离漏洞）。从 existing 匹配同时避免改到 DONE/EXPIRED 条目 + 省一次 DB 查询。
        existing.stream()
                .filter(e -> e.getId().equals(item.targetId()))
                .findFirst()
                .ifPresent(e -> {
                    e.setContent(item.content());
                    repository.save(e);
                });
    }

    private Instant computeSalientAt(MemoryItemKind kind, String dateHint) {
        ZoneId zone = clock.getZone();
        LocalTime nineAm = LocalTime.of(9, 0);
        if (kind == MemoryItemKind.PLAN_EVENT) {
            LocalDate eventDate = parseDateOrNull(dateHint);
            if (eventDate != null) {
                return eventDate.atTime(nineAm).atZone(zone).toInstant();
            }
            // 无法判断事件日 → 降级为次日 9:00（spec §4.2）
            return LocalDate.now(clock).plusDays(1).atTime(nineAm).atZone(zone).toInstant();
        }
        // EMOTION：观察日次日 9:00
        return LocalDate.now(clock).plusDays(1).atTime(nineAm).atZone(zone).toInstant();
    }

    private static LocalDate parseDateOrNull(String dateHint) {
        if (dateHint == null || dateHint.isBlank() || "null".equalsIgnoreCase(dateHint.trim())) {
            return null;
        }
        try {
            return LocalDate.parse(dateHint.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static MemoryItemKind parseKind(String kind) {
        if (kind == null) {
            return null;
        }
        try {
            return MemoryItemKind.valueOf(kind.trim().toUpperCase());
        } catch (Exception e) {
            return null;
        }
    }

    private String buildUserPrompt(String latestUserMessage, List<MemoryItemEntity> existing) {
        String today = LocalDate.now(clock).format(PROMPT_DATE_FMT);
        StringBuilder sb = new StringBuilder();
        sb.append("【今天的日期】\n").append(today)
                .append("（请据此把\"周三\"\"后天\"\"下周一\"等相对说法换算成具体的 ISO 日期填进 dateHint）\n\n");
        sb.append("【用户最新的一句话】\n").append(latestUserMessage == null ? "" : latestUserMessage).append("\n\n");
        sb.append("【当前已记录的待处理条目】\n");
        if (existing.isEmpty()) {
            sb.append("（暂无）\n");
        } else {
            for (MemoryItemEntity e : existing) {
                sb.append("- id=").append(e.getId())
                        .append(" [").append(e.getKind().name()).append("] ")
                        .append(e.getContent()).append("\n");
            }
        }
        return sb.toString();
    }
}
