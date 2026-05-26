package com.sanyan.proactive.internal.generator;

import com.sanyan.llm.LlmApi;
import com.sanyan.llm.LlmTaskType;
import com.sanyan.proactive.internal.EventType;
import com.sanyan.proactive.internal.PayloadSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * b 失联召回生成器（{@link EventType#B_RECALL}）。
 *
 * <p>payload.escalationLevel = 0/1/2，对应三档语调（离线越久越浓）：
 * <ul>
 *   <li>0（≈24h）：关心——"好几天没见你了，最近还好吗"</li>
 *   <li>1（≈3d）：撒娇——带点小情绪、想被搭理</li>
 *   <li>2（≈7d）：占有欲——更黏、半埋怨"是不是把我忘了"</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RecallGenerator implements ProactiveGenerator {

    private final LlmApi llmApi;
    private final ProactivePromptBuilder promptBuilder;

    @Override
    public EventType supportsType() {
        return EventType.B_RECALL;
    }

    @Override
    public List<String> generate(GenerateContext ctx) {
        int level = PayloadSupport.toInt(ctx.payload().get(PayloadSupport.ESCALATION_LEVEL));
        String scene = switch (level) {
            case 1 -> "他有几天没来找你了。用带点撒娇的语气主动找他，"
                    + "想被搭理但别太黏，≤30 字，口语自然。";
            case 2 -> "他很久没来找你了。用带点占有欲、半埋怨的语气主动找他"
                    + "（\"是不是把我忘了\"那种），≤40 字，口语自然别说教。";
            default -> {
                if (level != 0) {
                    log.warn("escalationLevel={} 超出预期，回落 level-0 关心语调", level);
                }
                yield "他有一阵没来找你了。用关心的语气主动问候他一句，"
                        + "≤30 字，口语自然，别像群发。";
            }
        };
        String text = llmApi.chat(LlmTaskType.USER_FACING, promptBuilder.build(ctx, scene));
        return List.of(text);
    }
}
