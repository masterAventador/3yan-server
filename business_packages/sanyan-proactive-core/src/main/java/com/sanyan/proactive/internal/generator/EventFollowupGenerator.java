package com.sanyan.proactive.internal.generator;

import com.sanyan.llm.LlmApi;
import com.sanyan.llm.LlmTaskType;
import com.sanyan.memory.MemoryApi;
import com.sanyan.memory.dto.MemoryItemDto;
import com.sanyan.proactive.internal.EventType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * c 事件追问生成器（{@link EventType#C_EVENT_FOLLOWUP}）。
 *
 * <p>payload.memoryItemId → {@code memoryApi.getMemoryItem} 取 content，
 * 注入场景指令让小婉追问那件事（"周三面试怎么样啦"）。
 * 条目查不到（已被删 / EXPIRED）时安静降级返回空 List，dispatcher 据此跳过。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EventFollowupGenerator implements ProactiveGenerator {

    private final LlmApi llmApi;
    private final MemoryApi memoryApi;
    private final ProactivePromptBuilder promptBuilder;

    @Override
    public EventType supportsType() {
        return EventType.C_EVENT_FOLLOWUP;
    }

    @Override
    public List<String> generate(GenerateContext ctx) {
        long itemId = toLong(ctx.payload().get("memoryItemId"));
        MemoryItemDto item;
        try {
            item = memoryApi.getMemoryItem(itemId);
        } catch (Exception e) {
            log.warn("事件追问：memory_item {} 取不到，跳过本次主动消息: {}", itemId, e.getMessage());
            return List.of();
        }

        String scene = "他之前跟你提过这件事：\"" + item.content() + "\"。"
                + "现在主动追问一下这件事的进展（自然地关心，别复述原话、别像查岗），≤40 字，口语。";
        String text = llmApi.chat(LlmTaskType.USER_FACING, promptBuilder.build(ctx, scene));
        return List.of(text);
    }

    private static long toLong(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        return o == null ? 0L : Long.parseLong(o.toString());
    }
}
