package com.sanyan.proactive.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanyan.character.CharacterApi;
import com.sanyan.character.dto.RelationshipDto;
import com.sanyan.chat.ChatApi;
import com.sanyan.common.error.BusinessException;
import com.sanyan.memory.MemoryApi;
import com.sanyan.memory.dto.MemoryContext;
import com.sanyan.proactive.internal.generator.GenerateContext;
import com.sanyan.proactive.internal.generator.ProactiveGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 分发层（spec §5.1 ③④⑤）：取 event → 频率门控 → 选生成器 → 组上下文 → 生成 → 委托投递 → 标状态。
 *
 * <p>不放行 → 标 {@link EventStatus#CANCELLED}（a/b 类丢弃；c/d 顺延逻辑由触发器/后续 task 处理）。
 * <p>本类只设置传入 entity 的状态字段 + 调外部 -api，**不调 repo 持久化**——持久化由
 * {@link ProactiveScheduler} 在其事务内 save（单一职责）。
 */
@Slf4j
@Service
public class ProactiveDispatcher {

    private static final String PAYLOAD_MEMORY_ITEM_ID = "memoryItemId";

    private final CharacterApi characterApi;
    private final MemoryApi memoryApi;
    private final ChatApi chatApi;
    private final FrequencyGate frequencyGate;
    private final ObjectMapper objectMapper;
    /** 按 EventType 索引的生成器（Spring 注入所有 ProactiveGenerator Bean，启动时建索引）。 */
    private final Map<EventType, ProactiveGenerator> generators;

    public ProactiveDispatcher(CharacterApi characterApi, MemoryApi memoryApi, ChatApi chatApi,
                               FrequencyGate frequencyGate, List<ProactiveGenerator> generatorList,
                               ObjectMapper objectMapper) {
        this.characterApi = characterApi;
        this.memoryApi = memoryApi;
        this.chatApi = chatApi;
        this.frequencyGate = frequencyGate;
        this.objectMapper = objectMapper;
        Map<EventType, ProactiveGenerator> map = new EnumMap<>(EventType.class);
        for (ProactiveGenerator g : generatorList) {
            EventType type = g.supportsType();
            if (type != null) {
                map.put(type, g);
            }
        }
        this.generators = map;
    }

    public void dispatch(EventPendingEntity event) {
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

        GenerateContext ctx = new GenerateContext(
                userId, characterId, relationship, stagePromptSegment, memoryContext, payload);
        List<String> segments = generator.generate(ctx);

        chatApi.deliverProactiveMessage(userId, characterId, segments);

        event.setStatus(EventStatus.SENT);
        event.setSentAt(Instant.now());
        frequencyGate.recordSent(userId);

        if (type == EventType.C_EVENT_FOLLOWUP || type == EventType.D_EMOTION_CARE) {
            Object itemId = payload.get(PAYLOAD_MEMORY_ITEM_ID);
            if (itemId != null) {
                memoryApi.markMemoryItemDone(((Number) itemId).longValue());
            }
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
