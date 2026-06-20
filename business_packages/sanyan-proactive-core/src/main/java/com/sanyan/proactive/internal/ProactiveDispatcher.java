package com.sanyan.proactive.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanyan.character.CharacterApi;
import com.sanyan.character.dto.RelationshipDto;
import com.sanyan.chat.ChatApi;
import com.sanyan.chat.dto.MessageDto;
import com.sanyan.common.error.BusinessException;
import com.sanyan.memory.MemoryApi;
import com.sanyan.memory.dto.MemoryContext;
import com.sanyan.proactive.internal.generator.GenerateContext;
import com.sanyan.proactive.internal.generator.ProactiveGenerator;
import com.sanyan.proactive.internal.PayloadSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 分发层（spec §5.1 ③④⑤ + 失败段）：取 event → 频率门控 → 选生成器 → 组上下文 → 生成 → 委托投递 → 标状态 → save。
 *
 * <p><b>{@code @Async} 隔离投递阻塞（H2 约束）：</b>{@link #dispatch} 标 {@code @Async}，由
 * {@link ProactiveScheduler}（不同 Bean）调用 → 跨 Bean 代理生效 → 在异步 worker 线程跑。
 * {@link ChatApi#deliverProactiveMessage} 内部每 segment 同步等 ACK（最多 5s，多条累加），
 * 若在 {@code @Scheduled(30s)} 单线程主循环里同步等会导致调度饥饿。fire-and-forget 后
 * 主循环立即返回，不被投递阻塞（DeliveryService Javadoc 明确要求 Dispatcher 异步隔离）。
 *
 * <p><b>一个 event 的完整生命周期内聚于此</b>：放行→生成→投递→标 SENT；门控拒→标 CANCELLED；
 * 任何异常→failCount++、超 {@link #MAX_FAIL} 标 FAILED 否则退避重排 SCHEDULED。
 * 无论成败 {@code finally} 里 {@link EventPendingRepository#save} 持久化终态。异常在 worker 内
 * catch 不外泄（@Async 抛出的异常调用方拿不到，必须自己消化）。
 */
@Slf4j
@Service
public class ProactiveDispatcher {

    /** 失败重试上限：超过则标 FAILED。 */
    static final int MAX_FAIL = 3;

    /** 反重复：喂回 prompt 的"最近已主动发过"消息条数上限。 */
    static final int RECENT_PROACTIVE_LIMIT = 5;

    private final CharacterApi characterApi;
    private final MemoryApi memoryApi;
    private final ChatApi chatApi;
    private final FrequencyGate frequencyGate;
    private final ObjectMapper objectMapper;
    private final EventPendingRepository repo;
    /** 按 EventType 索引的生成器（Spring 注入所有 ProactiveGenerator Bean，启动时建索引）。 */
    private final Map<EventType, ProactiveGenerator> generators;

    public ProactiveDispatcher(CharacterApi characterApi, MemoryApi memoryApi, ChatApi chatApi,
                               FrequencyGate frequencyGate, List<ProactiveGenerator> generatorList,
                               ObjectMapper objectMapper, EventPendingRepository repo) {
        this.characterApi = characterApi;
        this.memoryApi = memoryApi;
        this.chatApi = chatApi;
        this.frequencyGate = frequencyGate;
        this.objectMapper = objectMapper;
        this.repo = repo;
        Map<EventType, ProactiveGenerator> map = new EnumMap<>(EventType.class);
        for (ProactiveGenerator g : generatorList) {
            EventType type = g.supportsType();
            if (type != null) {
                map.put(type, g);
            }
        }
        this.generators = map;
    }

    /**
     * 异步分发一个 event 的完整生命周期。fire-and-forget：返回 void，异常在内部 catch 不外泄。
     * 由 {@link ProactiveScheduler} 跨 Bean 调用，@Async 代理生效在 worker 线程跑。
     */
    @Async
    public void dispatch(EventPendingEntity event) {
        try {
            deliver(event);              // 设置 SENT / CANCELLED
        } catch (Exception err) {
            handleFailure(event, err);   // 设置 FAILED / 退避 SCHEDULED
        } finally {
            repo.save(event);            // 无论成败持久化终态
        }
    }

    private void deliver(EventPendingEntity event) {
        Long userId = event.getUserId();
        Long characterId = event.getCharacterId();
        EventType type = event.getEventType();

        RelationshipDto relationship = characterApi.findOrCreateRelationship(userId, characterId);
        int stage = relationship.currentStage();

        if (!frequencyGate.allow(userId, characterId, type, stage)) {
            log.info("主动消息被门控拦截，标 CANCELLED: userId={}, type={}, stage={}", userId, type, stage);
            event.setStatus(EventStatus.CANCELLED);
            return;
        }

        ProactiveGenerator generator = generators.get(type);
        if (generator == null) {
            throw new BusinessException(ProactiveErrCode.PROACTIVE_GENERATE_FAILED,
                    "无可用生成器: " + type);
        }

        Map<String, Object> payload = parsePayload(event.getPayload());
        String stagePromptSegment = characterApi.getStagePromptSegment(userId, characterId);
        MemoryContext memoryContext = memoryApi.getRelevantContext(userId, characterId, "");

        // 反重复查询是"锦上添花"：跨模块 DB 查询失败时降级为空 list（不加反重复段、照常发），
        // 绝不能让它冒泡到 dispatch 的 catch 触发退避重排 / 标 FAILED，导致主动消息发不出去。
        List<String> recentProactive;
        try {
            recentProactive = chatApi.listRecentProactive(userId, RECENT_PROACTIVE_LIMIT)
                    .stream().map(MessageDto::content).toList();
        } catch (Exception e) {
            log.warn("查最近主动消息失败，本次不做反重复（降级）: userId={}, err={}", userId, e.getMessage());
            recentProactive = List.of();
        }

        GenerateContext ctx = new GenerateContext(
                userId, characterId, relationship, stagePromptSegment, memoryContext, payload, recentProactive);
        List<String> segments = generator.generate(ctx);

        chatApi.deliverProactiveMessage(userId, characterId, segments);

        // 消息已确定投出 —— SENT 是终态，下面的 recordSent / markMemoryItemDone 都是 best-effort
        // 副作用，各自包 try-catch：失败只 log.warn，绝不能把已确定的 SENT 覆盖回 SCHEDULED 触发重投。
        event.setStatus(EventStatus.SENT);
        event.setSentAt(Instant.now());

        try {
            frequencyGate.recordSent(userId);
        } catch (Exception e) {
            log.warn("主动消息已投出，recordSent 失败（best-effort 忽略）: userId={}, err={}", userId, e.getMessage());
        }

        if (type == EventType.C_EVENT_FOLLOWUP || type == EventType.D_EMOTION_CARE) {
            Object itemId = payload.get(PayloadSupport.MEMORY_ITEM_ID);
            if (itemId != null) {
                try {
                    memoryApi.markMemoryItemDone(PayloadSupport.toLong(itemId));
                } catch (Exception e) {
                    log.warn("主动消息已投出，markMemoryItemDone 失败（best-effort 忽略）: itemId={}, err={}",
                            itemId, e.getMessage());
                }
            }
        }
    }

    private void handleFailure(EventPendingEntity event, Exception err) {
        int newFailCount = event.getFailCount() + 1;
        event.setFailCount(newFailCount);
        event.setLastError(err.getMessage());
        if (newFailCount > MAX_FAIL) {
            event.setStatus(EventStatus.FAILED);
            log.error("主动消息分发超 {} 次，标 FAILED: eventId={}, userId={}, err={}",
                    MAX_FAIL, event.getId(), event.getUserId(), err.getMessage());
        } else {
            // 退避重排：线性退避，failCount 越大排越靠后
            event.setStatus(EventStatus.SCHEDULED);
            event.setScheduledAt(Instant.now().plusSeconds(60L * newFailCount));
            log.warn("主动消息分发失败第 {} 次，退避重排: eventId={}, userId={}, err={}",
                    newFailCount, event.getId(), event.getUserId(), err.getMessage());
        }
    }

    private Map<String, Object> parsePayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(payloadJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("payload 解析失败，按空处理: {}", payloadJson, e);
            return Collections.emptyMap();
        }
    }
}
