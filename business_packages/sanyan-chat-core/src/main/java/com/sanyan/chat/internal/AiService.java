package com.sanyan.chat.internal;

import com.sanyan.character.CharacterApi;
import com.sanyan.character.dto.AiCharacterDto;
import com.sanyan.chat.SenderType;
import com.sanyan.llm.LlmApi;
import com.sanyan.llm.LlmTaskType;
import com.sanyan.llm.dto.ChatMessage;
import com.sanyan.memory.MemoryApi;
import com.sanyan.memory.MemoryConstants;
import com.sanyan.memory.dto.MemoryContext;
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
 * <p>M3 task 重构：把豆包 HTTP 调用抽到 DoubaoAdapter（已迁 sanyan-llm-core），按
 * {@link LlmTaskType} 路由的逻辑放在 LLMProviderRouter。AiService 退化为薄编排层，只负责：
 * <ol>
 *   <li>加载人设资源文件 + 拼接当前时间组装 system prompt</li>
 *   <li>从 {@link MessageRepository} 拉取短期上下文（最近 {@link MemoryConstants#SHORT_TERM_WINDOW_SIZE} 条）</li>
 *   <li>调 {@link MemoryApi#getRelevantContext} 拿长期记忆整合 context（Q4）</li>
 *   <li>用 {@link PromptBuilder} 拼成 OpenAI 兼容消息数组</li>
 *   <li>转 {@link ChatMessage} 并委托给 {@link LlmApi}（task type = USER_FACING → 走豆包）</li>
 * </ol>
 *
 * <p>S3 Phase 3 重构：跨模块调 LLM 走 {@link LlmApi} 契约（不再直接持 LLMProviderRouter）。
 * S3 Phase 5：PromptBuilder 与本类一起搬到 sanyan-chat-core/internal/，仍输出
 * {@code List<Map<String,String>>}（历史 OpenAI 兼容形态），本类把它转成 {@code List<ChatMessage>}
 * 喂给 LlmApi。未来可考虑让 PromptBuilder 直接返回 {@code List<ChatMessage>} 省一次转换。
 *
 * <p>Q3 task 改动：
 * <ul>
 *   <li>短期窗口从硬编码 {@code 20} 改为 {@link MemoryConstants#SHORT_TERM_WINDOW_SIZE}（= 32），
 *       与摘要触发阈值的对齐铁律落地（{@code SHORT_TERM_WINDOW_SIZE > SUMMARY_TRIGGER_THRESHOLD}）</li>
 *   <li>消息拼装走 {@link PromptBuilder}，统一所有调用方（包括 -memory-core 后台 service）的拼装逻辑</li>
 * </ul>
 *
 * <p>Q4 task 改动：
 * <ul>
 *   <li>注入 {@link MemoryApi}，主对话前调 {@code getRelevantContext} 拿长期记忆 context 传给 PromptBuilder</li>
 *   <li>RAG query 取最近一条 {@link SenderType#USER} 消息内容；无 user 消息时用空串占位</li>
 *   <li>characterId 优先 {@code character.id()}；为 null 时回落到
 *       {@code sanyan.memory.default-character-id}（默认 1L，MVP 单角色阶段的兜底）</li>
 *   <li><b>降级</b>：MemoryApi 抛异常时 catch + {@code log.warn} 跳过长期记忆，主对话继续不受影响</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiService {

    private final MessageRepository messageRepository;
    private final LlmApi llmApi;
    private final PromptBuilder promptBuilder;
    private final MemoryApi memoryApi;
    private final CharacterApi characterApi;

    @Value("classpath:prompts/xiaowan-system.md")
    private Resource systemPromptResource;

    /**
     * Plan 1 MVP 单角色阶段的兜底 characterId。
     * <p>chat 入参 {@link AiCharacterDto} 通常带 id（来自 DB 经 CharacterApi 查询），但容错路径上 character 可能没 id
     * （例如 fixture 未持久化、未来某角色字段缺失等），此时回落到本默认值，与 N3/O4 的硬编码 1L 对齐。
     * <p>通过 {@code @Value} 注入，方便后续 application.yml 显式配置或多角色 Plan 3 直接弃用。
     */
    @Value("${sanyan.memory.default-character-id:1}")
    private Long defaultCharacterId;

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
     * <p>流程：
     * <ol>
     *   <li>组装 system prompt（人设 + 当前时间）</li>
     *   <li>拉取最近 {@link MemoryConstants#SHORT_TERM_WINDOW_SIZE} 条短期消息，reverse 成时间正序</li>
     *   <li>取最新一条用户消息作为 RAG query，调 {@link MemoryApi#getRelevantContext} 拿
     *       长期记忆 context（异常降级为 null）</li>
     *   <li>用 {@link PromptBuilder} 拼装 OpenAI 消息数组，转 {@link ChatMessage} 后委托 {@link LlmApi}</li>
     * </ol>
     */
    public String chat(AiCharacterDto character, Long userId) {
        String systemPrompt = assembleSystemPrompt(systemPromptTemplate, formatCurrentTime());

        List<MessageEntity> recentMessages = messageRepository
                .findByUserIdOrderByIdDesc(userId, PageRequest.of(0, MemoryConstants.SHORT_TERM_WINDOW_SIZE));
        Collections.reverse(recentMessages);

        Long characterId = resolveCharacterId(character);
        String ragQuery = extractLatestUserMessage(recentMessages);

        MemoryContext memoryContext = null;
        try {
            memoryContext = memoryApi.getRelevantContext(userId, characterId, ragQuery);
        } catch (Exception e) {
            // 长期记忆失败不能影响主对话——降级跳过长期记忆段，继续走短期窗口 + 人设
            log.warn("MemoryApi 调用失败，跳过长期记忆: {}", e.getMessage());
        }

        String stagePromptSegment = "";
        try {
            stagePromptSegment = characterApi.getStagePromptSegment(userId, characterId);
        } catch (Exception e) {
            // CharacterApi 失败不能影响主对话——降级跳过 stage prompt，与 MemoryApi 降级策略对称
            log.warn("CharacterApi.getStagePromptSegment 失败，跳过 stage prompt: {}", e.getMessage());
        }
        List<Map<String, String>> openAiMessages =
                promptBuilder.build(systemPrompt, stagePromptSegment, memoryContext, recentMessages);
        List<ChatMessage> chatMessages = openAiMessages.stream()
                .map(m -> new ChatMessage(m.get("role"), m.get("content")))
                .toList();
        return llmApi.chat(LlmTaskType.USER_FACING, chatMessages);
    }

    public String assembleSystemPrompt(String characterPrompt, String time) {
        return characterPrompt + "\n\n[当前时间] " + time;
    }

    private String formatCurrentTime() {
        LocalDateTime now = LocalDateTime.now();
        return now.format(DateTimeFormatter.ofPattern("yyyy年M月d日 E HH:mm", Locale.CHINESE));
    }

    /**
     * 从短期窗口里取「最新一条 user 消息」的内容，作为 RAG 检索 query。
     * <p>没有 user 消息时返回空串（不返回 null，避免 -core 端 NPE）。
     */
    private static String extractLatestUserMessage(List<MessageEntity> recentMessagesAsc) {
        if (recentMessagesAsc == null) {
            return "";
        }
        for (int i = recentMessagesAsc.size() - 1; i >= 0; i--) {
            MessageEntity msg = recentMessagesAsc.get(i);
            if (SenderType.USER.equalsIgnoreCase(msg.getSenderType())) {
                return msg.getContent() == null ? "" : msg.getContent();
            }
        }
        return "";
    }

    /**
     * 优先 {@code character.id()}，否则回落 {@link #defaultCharacterId}。
     */
    private Long resolveCharacterId(AiCharacterDto character) {
        if (character != null && character.id() != null) {
            return character.id();
        }
        return defaultCharacterId;
    }
}
