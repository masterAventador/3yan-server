package com.sanyan.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanyan.dto.ws.SenderType;
import com.sanyan.entity.AiCharacter;
import com.sanyan.entity.Message;
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
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    @Value("${sanyan.doubao.api-key:}")
    private String apiKey;

    @Value("${sanyan.doubao.model:doubao-seed-character}")
    private String model;

    @Value("${sanyan.doubao.endpoint:https://ark.cn-beijing.volces.com/api/v3/chat/completions}")
    private String endpoint;

    static final String AI_FALLBACK_MESSAGE = "抱歉，我现在有点走神了，等下再聊吧~";

    /**
     * AI reply to user's text message.
     * 一期短期上下文：最近 20 条消息。长期记忆（B+C+RAG）按 spec Plan 2 实现。
     */
    public String chat(AiCharacter character, Long userId) {
        String systemPrompt = assembleSystemPrompt(character.getSystemPrompt(), formatCurrentTime());

        List<Message> recentMessages = messageRepository
                .findByUserIdOrderByIdDesc(userId, PageRequest.of(0, 20));
        Collections.reverse(recentMessages);

        return callDoubao(systemPrompt, recentMessages);
    }

    public String assembleSystemPrompt(String characterPrompt, String time) {
        return characterPrompt + "\n\n[当前时间] " + time;
    }

    public String callDoubao(String systemPrompt, List<Message> messages) {
        return callDoubaoRaw(buildChatMessages(systemPrompt, messages));
    }

    public String callDoubaoRaw(List<Map<String, String>> chatMessages) {
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);
            requestBody.put("messages", chatMessages);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(requestBody), headers);

            log.info("豆包 API 请求: model={}, messagesCount={}", model, chatMessages.size());
            long start = System.currentTimeMillis();
            ResponseEntity<String> response = restTemplate.exchange(endpoint, HttpMethod.POST, entity, String.class);
            log.info("豆包 API 响应: status={}, 耗时={}ms", response.getStatusCode(), System.currentTimeMillis() - start);

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

    static List<Map<String, String>> buildChatMessages(String systemPrompt, List<Message> messages) {
        List<Map<String, String>> chatMessages = new ArrayList<>();
        chatMessages.add(Map.of("role", "system", "content", systemPrompt));
        if (messages != null) {
            for (Message msg : messages) {
                String role = SenderType.USER.equals(msg.getSenderType()) ? "user" : "assistant";
                chatMessages.add(Map.of("role", role, "content", msg.getContent() == null ? "" : msg.getContent()));
            }
        }
        return chatMessages;
    }

    private String formatCurrentTime() {
        LocalDateTime now = LocalDateTime.now();
        return now.format(DateTimeFormatter.ofPattern("yyyy年M月d日 E HH:mm", Locale.CHINESE));
    }
}
