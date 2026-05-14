package com.sanyan.llm.internal;

import com.sanyan.character.internal.AiCharacterEntity;
import com.sanyan.chat.internal.MessageEntity;
import com.sanyan.chat.internal.MessageRepository;
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

/**
 * AI 对话编排层。
 *
 * <p>M3 task 重构：把豆包 HTTP 调用抽到 {@link DoubaoAdapter}，按 {@link LLMTaskType} 路由
 * 的逻辑搬到 {@link LLMProviderRouter}。AiService 退化为薄编排层，只负责：
 * <ol>
 *   <li>加载人设资源文件 + 拼接当前时间组装 system prompt</li>
 *   <li>从 {@link MessageRepository} 拉取短期上下文（最近 20 条）</li>
 *   <li>委托给 {@link LLMProviderRouter}（task type = USER_FACING → 走豆包）</li>
 * </ol>
 *
 * <p>原 {@code callDoubao} / {@code callDoubaoRaw} / {@code buildChatMessages} / fallback 字符串
 * / 直接 @Value 注入的豆包配置全部移除——router + adapter 接管。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiService {

    private final MessageRepository messageRepository;
    private final LLMProviderRouter llmRouter;

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
     * <p>一期短期上下文：最近 20 条消息。长期记忆（B+C+RAG）按 Plan 2 后续 Phase 实现。
     */
    public String chat(AiCharacterEntity character, Long userId) {
        String systemPrompt = assembleSystemPrompt(systemPromptTemplate, formatCurrentTime());

        List<MessageEntity> recentMessages = messageRepository
                .findByUserIdOrderByIdDesc(userId, PageRequest.of(0, 20));
        Collections.reverse(recentMessages);

        return llmRouter.chat(LLMTaskType.USER_FACING, systemPrompt, recentMessages);
    }

    public String assembleSystemPrompt(String characterPrompt, String time) {
        return characterPrompt + "\n\n[当前时间] " + time;
    }

    private String formatCurrentTime() {
        LocalDateTime now = LocalDateTime.now();
        return now.format(DateTimeFormatter.ofPattern("yyyy年M月d日 E HH:mm", Locale.CHINESE));
    }
}
