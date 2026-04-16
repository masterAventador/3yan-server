package com.sanyan.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanyan.dto.ws.SenderType;
import com.sanyan.entity.AiCharacter;
import com.sanyan.entity.MemoryProfile;
import com.sanyan.entity.MemorySummary;
import com.sanyan.entity.Message;
import com.sanyan.repository.MemoryProfileRepository;
import com.sanyan.repository.MemorySummaryRepository;
import com.sanyan.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiService {

    private final MessageRepository messageRepository;
    private final MemoryProfileRepository memoryProfileRepository;
    private final MemorySummaryRepository memorySummaryRepository;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    @Value("${sanyan.doubao.api-key:}")
    private String apiKey;

    @Value("${sanyan.doubao.model:doubao-seed-character}")
    private String model;

    @Value("${sanyan.doubao.endpoint:https://ark.cn-beijing.volces.com/api/v3/chat/completions}")
    private String endpoint;

    /**
     * Fallback message returned when doubao API call fails.
     * Used by chat/chatVoiceAck (user-triggered) as a graceful degradation.
     * For chatProactive (AI-initiated), this string triggers a null return — see chatProactive().
     */
    static final String AI_FALLBACK_MESSAGE = "抱歉，我现在有点走神了，等下再聊吧~";

    /**
     * AI reply to user message
     */
    public String chat(AiCharacter character, Long conversationId) {
        String time = formatCurrentTime();
        String profile = getProfile(conversationId);
        String systemPrompt = assembleSystemPrompt(character.getSystemPrompt(), time, profile);

        List<MemorySummary> summaries = memorySummaryRepository
                .findByConversationIdOrderByCreatedAtDesc(conversationId, PageRequest.of(0, 5));
        List<Message> recentMessages = messageRepository
                .findByConversationIdOrderByIdDesc(conversationId, PageRequest.of(0, 20));
        Collections.reverse(recentMessages);

        return callDoubao(systemPrompt, summaries, recentMessages);
    }

    /**
     * 用户发了语音但当前不支持 ASR，让 AI 生成"没听清"的撒娇回复。
     */
    public String chatVoiceAck(AiCharacter character, Long conversationId) {
        String time = formatCurrentTime();
        String profile = getProfile(conversationId);
        String basePrompt = assembleSystemPrompt(character.getSystemPrompt(), time, profile);
        String voicePrompt = basePrompt
            + "\n\n[特殊场景] 用户刚才发了一条语音消息，但你现在还无法听懂语音内容。"
            + "请用符合你人设的方式回复，表达你听到了但没听清，让用户再说一遍或者打字告诉你。"
            + "要自然、多变、撒娇，每次都不一样。记得按规则输出语音情感标签。";

        List<MemorySummary> summaries = memorySummaryRepository
                .findByConversationIdOrderByCreatedAtDesc(conversationId, PageRequest.of(0, 5));
        List<Message> recentMessages = messageRepository
                .findByConversationIdOrderByIdDesc(conversationId, PageRequest.of(0, 20));
        Collections.reverse(recentMessages);

        return callDoubao(voicePrompt, summaries, recentMessages);
    }

    /**
     * Build messages array for proactive trigger.
     * NO conversation history is passed — proactive should be a fresh initiation,
     * not a continuation of past dialogue.
     */
    static List<Map<String, String>> buildProactiveMessages(
            String characterPrompt,
            String time,
            String profile,
            List<MemorySummary> summaries,
            String triggerHint) {

        StringBuilder systemContent = new StringBuilder(characterPrompt);
        systemContent.append("\n\n[当前时间] ").append(time);
        if (profile != null && !profile.isBlank()) {
            systemContent.append("\n\n[你对用户的印象] ").append(profile);
        }
        if (summaries != null && !summaries.isEmpty()) {
            systemContent.append("\n\n[你们以前聊过的事]");
            for (MemorySummary s : summaries) {
                systemContent.append("\n- ").append(s.getSummary());
            }
        }

        String userContent = "【这不是用户的话，是给你的系统指令】\n"
                + triggerHint + "\n"
                + "注意：\n"
                + "- 这是你主动发起的消息，不要写成在回复用户上一句\n"
                + "- 不要直接复述以前聊过的事，那是你的记忆，不是要拿来重复\n"
                + "- 考虑当前时间，措辞要符合场景\n"
                + "- 直接输出消息内容，不要带任何解释或前缀";

        return List.of(
                Map.of("role", "system", "content", systemContent.toString()),
                Map.of("role", "user", "content", userContent)
        );
    }

    /**
     * AI proactive message (with trigger hint)
     */
    public String chatProactive(AiCharacter character, Long conversationId, String triggerHint) {
        String time = formatCurrentTime();
        String profile = getProfile(conversationId);

        List<MemorySummary> summaries = memorySummaryRepository
                .findByConversationIdOrderByCreatedAtDesc(conversationId, PageRequest.of(0, 5));

        List<Map<String, String>> messages = buildProactiveMessages(
                character.getSystemPrompt(), time, profile, summaries, triggerHint);

        String result = callDoubaoRaw(messages);
        if (AI_FALLBACK_MESSAGE.equals(result)) {
            log.warn("豆包 API 失败，主动消息放弃。conversationId={}", conversationId);
            return null;
        }
        return result;
    }

    /**
     * Assemble system prompt with time and user profile
     */
    public String assembleSystemPrompt(String characterPrompt, String time, String profile) {
        StringBuilder sb = new StringBuilder();
        sb.append(characterPrompt);
        sb.append("\n\n[当前时间] ").append(time);
        if (profile != null && !profile.isBlank()) {
            sb.append("\n\n[用户画像] ").append(profile);
        }
        return sb.toString();
    }

    /**
     * Call doubao API with raw chat messages (already-built role/content list).
     */
    public String callDoubaoRaw(List<Map<String, String>> chatMessages) {
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);
            requestBody.put("messages", chatMessages);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            String body = objectMapper.writeValueAsString(requestBody);
            HttpEntity<String> entity = new HttpEntity<>(body, headers);

            log.info("豆包 API 请求: model={}, messagesCount={}", model, chatMessages.size());
            long start = System.currentTimeMillis();
            ResponseEntity<String> response = restTemplate.exchange(endpoint, HttpMethod.POST, entity, String.class);
            long elapsed = System.currentTimeMillis() - start;
            log.info("豆包 API 响应: status={}, 耗时={}ms", response.getStatusCode(), elapsed);

            @SuppressWarnings("unchecked")
            Map<String, Object> responseMap = objectMapper.readValue(response.getBody(), Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> choices = (List<Map<String, Object>>) responseMap.get("choices");
            @SuppressWarnings("unchecked")
            Map<String, String> message = (Map<String, String>) choices.get(0).get("message");
            return message.get("content");

        } catch (Exception e) {
            log.error("豆包 API 调用失败", e);
            return AI_FALLBACK_MESSAGE;
        }
    }

    /**
     * Call doubao API (OpenAI-compatible chat completion)
     */
    public String callDoubao(String systemPrompt, List<MemorySummary> summaries, List<Message> messages) {
        List<Map<String, String>> chatMessages = new ArrayList<>();
        chatMessages.add(Map.of("role", "system", "content", systemPrompt));

        if (summaries != null && !summaries.isEmpty()) {
            StringBuilder summaryText = new StringBuilder("[历史记忆摘要]\n");
            for (MemorySummary s : summaries) {
                summaryText.append("- ").append(s.getSummary()).append("\n");
            }
            chatMessages.add(Map.of("role", "system", "content", summaryText.toString()));
        }

        if (messages != null) {
            for (Message msg : messages) {
                String role = SenderType.USER.equals(msg.getSenderType()) ? "user" : "assistant";
                chatMessages.add(Map.of("role", role, "content", msg.getContent()));
            }
        }

        return callDoubaoRaw(chatMessages);
    }

    /**
     * Simple call for memory extraction (Task 9 will use)
     */
    public String callForMemory(String prompt) {
        try {
            List<Map<String, String>> chatMessages = new ArrayList<>();
            chatMessages.add(Map.of("role", "user", "content", prompt));

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);
            requestBody.put("messages", chatMessages);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            String body = objectMapper.writeValueAsString(requestBody);
            HttpEntity<String> entity = new HttpEntity<>(body, headers);

            ResponseEntity<String> response = restTemplate.exchange(endpoint, HttpMethod.POST, entity, String.class);

            @SuppressWarnings("unchecked")
            Map<String, Object> responseMap = objectMapper.readValue(response.getBody(), Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> choices = (List<Map<String, Object>>) responseMap.get("choices");
            @SuppressWarnings("unchecked")
            Map<String, String> message = (Map<String, String>) choices.get(0).get("message");
            return message.get("content");

        } catch (Exception e) {
            log.error("豆包 API 记忆提取调用失败", e);
            return null;
        }
    }

    private String getProfile(Long conversationId) {
        return memoryProfileRepository.findByConversationId(conversationId)
                .map(MemoryProfile::getContent)
                .orElse(null);
    }

    private String formatCurrentTime() {
        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy年M月d日 E HH:mm", Locale.CHINESE);
        return now.format(formatter);
    }
}
