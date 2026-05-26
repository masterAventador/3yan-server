package com.sanyan.proactive.internal.generator;

import com.sanyan.llm.LlmApi;
import com.sanyan.llm.LlmTaskType;
import com.sanyan.proactive.internal.EventType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * a 早晚安生成器（{@link EventType#A_GREETING}）。
 *
 * <p>payload.timeOfDay = {@code "morning"} / {@code "night"}，按 stage 语调发一句问候：
 * ≤30 字、口语、避免"早上好"这类生硬开头。
 */
@Component
@RequiredArgsConstructor
public class GreetingGenerator implements ProactiveGenerator {

    static final String TIME_MORNING = "morning";

    private final LlmApi llmApi;
    private final ProactivePromptBuilder promptBuilder;

    @Override
    public EventType supportsType() {
        return EventType.A_GREETING;
    }

    @Override
    public List<String> generate(GenerateContext ctx) {
        String timeOfDay = Objects.toString(ctx.payload().get("timeOfDay"), TIME_MORNING);
        boolean morning = TIME_MORNING.equals(timeOfDay);
        String scene = morning
                ? "现在是早上，主动给他发一句早安问候。要自然口语、≤30 字，"
                + "不要用\"早上好\"\"早安\"这种生硬开头，像真的想起他随口发的一条。"
                : "现在是晚上，主动给他发一句晚安问候。要自然口语、≤30 字，"
                + "不要用\"晚安好梦\"这种套话开头，像睡前随口关心一句。";

        String text = llmApi.chat(LlmTaskType.USER_FACING, promptBuilder.build(ctx, scene));
        return List.of(text);
    }
}
