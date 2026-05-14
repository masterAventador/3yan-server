package com.sanyan.llm.internal;

import com.sanyan.character.internal.AiCharacterEntity;
import com.sanyan.chat.internal.MessageEntity;
import com.sanyan.chat.internal.MessageRepository;
import com.sanyan.memory.MemoryConstants;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AI 对话编排层。
 *
 * <p>M3 task 重构：把豆包 HTTP 调用抽到 {@link DoubaoAdapter}，按 {@link LLMTaskType} 路由
 * 的逻辑搬到 {@link LLMProviderRouter}。AiService 退化为薄编排层，只负责：
 * <ol>
 *   <li>加载人设资源文件 + 拼接当前时间组装 system prompt</li>
 *   <li>从 {@link MessageRepository} 拉取短期上下文（最近 {@link MemoryConstants#SHORT_TERM_WINDOW_SIZE} 条）</li>
 *   <li>用 {@link PromptBuilder} 拼成 OpenAI 兼容消息数组</li>
 *   <li>委托给 {@link LLMProviderRouter}（task type = USER_FACING → 走豆包）</li>
 * </ol>
 *
 * <p>Q3 task 改动：
 * <ul>
 *   <li>短期窗口从硬编码 {@code 20} 改为 {@link MemoryConstants#SHORT_TERM_WINDOW_SIZE}（= 32），
 *       与摘要触发阈值的对齐铁律落地（{@code SHORT_TERM_WINDOW_SIZE > SUMMARY_TRIGGER_THRESHOLD}）</li>
 *   <li>消息拼装走 {@link PromptBuilder}，统一所有调用方（包括 -memory-core 后台 service）的拼装逻辑</li>
 *   <li>{@code memoryContext} 暂传 null，Q4 task 才注入 {@code MemoryApi} 拿真实上下文</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiService {

    private final MessageRepository messageRepository;
    private final LLMProviderRouter llmRouter;
    private final PromptBuilder promptBuilder;

    @Value("classpath:prompts/xiaowan-system.md")
    private Resource systemPromptResource;

    private String systemPromptTemplate;

    @PostConstruct
    void loadSystemPrompt() {
        try (InputStream is = systemPromptResource.getInputStream()) {
            this.systemPromptTemplate = new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new IllegalStateException("加载人设资源文件失败: " + systemPromptResource, e);
        }
    }

    /**
     * AI 回复用户消息。
     *
     * <p>短期上下文：最近 {@link MemoryConstants#SHORT_TERM_WINDOW_SIZE} 条消息。
     * 长期记忆（profile + summary + RAG）由 Q4 task 通过 {@code MemoryApi} 注入。
     */
    public String chat(AiCharacterEntity character, Long userId) {
        String systemPrompt = assembleSystemPrompt(systemPromptTemplate, formatCurrentTime());

        List<MessageEntity> recentMessages = messageRepository
                .findByUserIdOrderByIdDesc(userId, PageRequest.of(0, MemoryConstants.SHORT_TERM_WINDOW_SIZE));
        Collections.reverse(recentMessages);

        // memoryContext = null：本 task（Q3）只完成 PromptBuilder 抽取 + 短期窗口对齐。
        // Q4 task 才注入 MemoryApi 拿到真实 MemoryContext 传给 PromptBuilder。
        List<Map<String, String>> openAiMessages =
                promptBuilder.build(systemPrompt, null, recentMessages);
        return llmRouter.chat(LLMTaskType.USER_FACING, openAiMessages);
    }

    public String assembleSystemPrompt(String characterPrompt, String time) {
        return characterPrompt + "\n\n[当前时间] " + time;
    }

    private String formatCurrentTime() {
        LocalDateTime now = LocalDateTime.now();
        return now.format(DateTimeFormatter.ofPattern("yyyy年M月d日 E HH:mm", Locale.CHINESE));
    }
}
