package com.sanyan.proactive.internal.generator;

import com.sanyan.common.util.SpokenChineseTime;
import com.sanyan.llm.dto.ChatMessage;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 把生成器上下文拼成 LLM 的 {@code List<ChatMessage>}。
 *
 * <p>拼装顺序对齐 chat-core {@code PromptBuilder}：
 * <ol>
 *   <li>system: 基底人设（{@link #PERSONA_BASE}）+ stagePromptSegment（非 blank）
 *       + 记忆段（memoryContext.text() 非 blank）—— 合并为一条 system message</li>
 *   <li>user: 场景指令（caller 拼好的"现在请你……"，由各 Generator 传入）</li>
 * </ol>
 *
 * <p>注入 {@link Clock}（全局 ClockConfig 提供）以便单测固定时间，断言 system 段的"当前时间"锚点。
 */
@Component
public class ProactivePromptBuilder {

    /**
     * 基底人设占位。
     * <p>说明：理想情况应从 character-api 拿基底 base_prompt（小婉人设），
     * 但本期 character-api 未暴露 base_prompt 查询方法，先用占位常量保证语气不跑偏；
     * stage 语调 / 记忆由后续段补齐。base_prompt 接入列为待办（不阻塞主流程）。
     */
    static final String PERSONA_BASE =
            "你是小婉，用户的 AI 伴侣。下面是你主动找用户聊天的场景——按你的人设和当前关系语气，自然地开口，"
                    + "像真人发消息一样口语、简短，不要像客服或机器人。";

    static final String MEMORY_PREFIX = "她记得关于你的事：\n";

    private final Clock clock;

    public ProactivePromptBuilder(Clock clock) {
        this.clock = clock;
    }

    /**
     * @param ctx              生成上下文
     * @param sceneInstruction 场景指令（user 段），由各 Generator 按场景拼好传入
     * @return system + user 两条 ChatMessage
     */
    public List<ChatMessage> build(GenerateContext ctx, String sceneInstruction) {
        StringBuilder system = new StringBuilder(PERSONA_BASE);
        system.append("\n\n").append(SpokenChineseTime.CURRENT_TIME_PREFIX)
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
