package com.sanyan.chat.internal;

import com.sanyan.chat.SenderType;
import com.sanyan.memory.MemoryConstants;
import com.sanyan.memory.dto.MemoryContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Plan 2 Task Q3：把 LLM 调用前的「OpenAI 兼容消息数组」拼装逻辑统一收口。
 *
 * <p>原 M3 task 把这段拼装放在 {@code LLMProviderRouter#buildOpenAiMessages} 里——但 Q3 task
 * 需要在 system 段注入长期记忆（{@link MemoryContext}），并且短期窗口必须严格限制在
 * {@link MemoryConstants#SHORT_TERM_WINDOW_SIZE}（= 32）条以内。把拼装抽到独立类有两个好处：
 * <ul>
 *   <li>{@code AiService} / {@code MemorySummaryService} / {@code MemoryProfileRefreshService}
 *       共用同一份拼装逻辑（值复用 / 逻辑复用 双重）</li>
 *   <li>Router 退化为纯路由层，只关心"task type → provider 选择"，不再混入拼装</li>
 * </ul>
 *
 * <p>拼装顺序（强约束）：
 * <ol>
 *   <li>system: {@code characterPrompt}（人设 + 当前时间等基底）</li>
 *   <li>system: 「她对你的记忆：\n」+ {@code memoryContext.text()}（仅当 memoryContext 非空且非 blank）</li>
 *   <li>历史消息按入参顺序追加，最多保留尾部 {@link MemoryConstants#SHORT_TERM_WINDOW_SIZE} 条</li>
 * </ol>
 *
 * <p>SenderType 映射：
 * <ul>
 *   <li>{@link SenderType#USER}（即 "user"） → role=user</li>
 *   <li>其他（"ai"） → role=assistant</li>
 *   <li>{@code null} content → 空串（防止 OpenAI / 上游 LLM 因 null 报 400）</li>
 * </ul>
 *
 * <p>本类无实例字段，但是注册成 Spring Bean 是为了让调用方走 DI（注入 PromptBuilder），
 * 便于后续替换实现或在测试里 mock；常量逻辑本身没有 this 依赖，方法体保持纯函数。
 */
@Component
public class PromptBuilder {

    /** 记忆段的固定前缀。统一前缀让 LLM prompt 风格稳定，也方便人工 review 时辨认。 */
    static final String MEMORY_PREFIX = "她对你的记忆：\n";

    /**
     * 拼接 OpenAI Chat Completion 兼容的消息数组。
     *
     * @param characterPrompt 人设 / 角色 system prompt 文本（可为 null 或 blank，但通常非空）
     * @param memoryContext   长期记忆整合上下文，为 null / EMPTY / blank 时跳过该 system 段
     * @param recentMessages  按时间正序的最近消息列表（如超过 {@link MemoryConstants#SHORT_TERM_WINDOW_SIZE}
     *                        条，只保留尾部 32 条）
     * @return OpenAI 兼容的 {@code [{role, content}, ...]} 数组（保证非 null，可能为空列表）
     */
    public List<Map<String, String>> build(
            String characterPrompt,
            MemoryContext memoryContext,
            List<MessageEntity> recentMessages) {

        List<Map<String, String>> messages = new ArrayList<>();

        if (characterPrompt != null && !characterPrompt.isBlank()) {
            messages.add(Map.of("role", "system", "content", characterPrompt));
        }

        if (memoryContext != null && !memoryContext.isEmpty()) {
            messages.add(Map.of("role", "system", "content", MEMORY_PREFIX + memoryContext.text()));
        }

        List<MessageEntity> limited = limitToWindow(recentMessages);
        for (MessageEntity msg : limited) {
            String role = SenderType.USER.equals(msg.getSenderType()) ? "user" : "assistant";
            String content = msg.getContent() == null ? "" : msg.getContent();
            messages.add(Map.of("role", role, "content", content));
        }

        return messages;
    }

    /**
     * 截取列表尾部不超过 {@link MemoryConstants#SHORT_TERM_WINDOW_SIZE} 条。
     *
     * <p>语义：调用方应该按时间正序传入，"尾部"即"最新"。超过窗口时丢弃最早的几条，
     * 保留最近的对话上下文。
     */
    private static List<MessageEntity> limitToWindow(List<MessageEntity> messages) {
        if (messages == null) {
            return List.of();
        }
        int size = messages.size();
        int window = MemoryConstants.SHORT_TERM_WINDOW_SIZE;
        if (size <= window) {
            return messages;
        }
        return messages.subList(size - window, size);
    }
}
