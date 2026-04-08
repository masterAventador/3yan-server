package com.sanyan.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
     * AI proactive message (with trigger hint)
     */
    public String chatProactive(AiCharacter character, Long conversationId, String triggerHint) {
        String time = formatCurrentTime();
        String profile = getProfile(conversationId);
        String basePrompt = assembleSystemPrompt(character.getSystemPrompt(), time, profile);
        String systemPrompt = basePrompt + "\n\n[主动触发提示] " + triggerHint;

        List<MemorySummary> summaries = memorySummaryRepository
                .findByConversationIdOrderByCreatedAtDesc(conversationId, PageRequest.of(0, 5));
        List<Message> recentMessages = messageRepository
                .findByConversationIdOrderByIdDesc(conversationId, PageRequest.of(0, 20));
        Collections.reverse(recentMessages);

        return callDoubao(systemPrompt, summaries, recentMessages);
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
     * Call doubao API (OpenAI-compatible chat completion)
     */
    public String callDoubao(String systemPrompt, List<MemorySummary> summaries, List<Message> messages) {
        try {
            List<Map<String, String>> chatMessages = new ArrayList<>();
            chatMessages.add(Map.of("role", "system", "content", systemPrompt));

            // Add summaries as system context
            if (summaries != null && !summaries.isEmpty()) {
                StringBuilder summaryText = new StringBuilder("[历史记忆摘要]\n");
                for (MemorySummary s : summaries) {
                    summaryText.append("- ").append(s.getSummary()).append("\n");
                }
                chatMessages.add(Map.of("role", "system", "content", summaryText.toString()));
            }

            // Add recent messages
            if (messages != null) {
                for (Message msg : messages) {
                    String role = "user".equals(msg.getSenderType()) ? "user" : "assistant";
                    chatMessages.add(Map.of("role", role, "content", msg.getContent()));
                }
            }

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
            return "抱歉，我现在有点走神了，等下再聊吧~";
        }
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
