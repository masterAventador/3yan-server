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
 * d 情绪关怀生成器（{@link EventType#D_EMOTION_CARE}）。
 *
 * <p>payload.memoryItemId → {@code memoryApi.getMemoryItem} 取 EMOTION 条目 content，
 * <b>间接</b>关心：不直接提"你昨天说难过"，而是用一句温柔日常的关心去贴近那个情绪。
 * 条目查不到时安静降级返回空 List。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EmotionCareGenerator implements ProactiveGenerator {

    private final LlmApi llmApi;
    private final MemoryApi memoryApi;
    private final ProactivePromptBuilder promptBuilder;

    @Override
    public EventType supportsType() {
        return EventType.D_EMOTION_CARE;
    }

    @Override
    public List<String> generate(GenerateContext ctx) {
        long itemId = toLong(ctx.payload().get("memoryItemId"));
        MemoryItemDto item;
        try {
            item = memoryApi.getMemoryItem(itemId);
        } catch (Exception e) {
            log.warn("情绪关怀：memory_item {} 取不到，跳过本次主动消息: {}", itemId, e.getMessage());
            return List.of();
        }

        String scene = "你最近观察到他的情绪状态是：\"" + item.content() + "\"。"
                + "现在用一句温柔的日常关心去贴近他这个情绪——"
                + "要间接，不要直接复述或点破他的情绪（比如不要说\"你昨天说很焦虑\"），"
                + "用关心生活的小事（吃饭/睡觉/天气）侧面传达\"我在意你\"，≤30 字，口语。";
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
