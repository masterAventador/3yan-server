package com.sanyan.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanyan.dto.data.MessageData;
import com.sanyan.dto.ws.MessageContentType;
import com.sanyan.dto.ws.SenderType;
import com.sanyan.dto.ws.WsNewMessage;
import com.sanyan.dto.ws.WsTyping;
import com.sanyan.entity.AiCharacter;
import com.sanyan.entity.Conversation;
import com.sanyan.entity.Message;
import com.sanyan.repository.AiCharacterRepository;
import com.sanyan.repository.ConversationRepository;
import com.sanyan.repository.MessageRepository;
import com.sanyan.util.TextProcessor;
import com.sanyan.websocket.SessionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * AI Proactive Messaging Service.
 * Handles rate limiting, anti-harassment checks, and delivery (online WebSocket or offline push).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProactiveService {

    private static final String DAILY_COUNT_PREFIX = "proactive:daily:";
    private static final String LAST_TS_PREFIX = "proactive:last:";

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final AiCharacterRepository characterRepository;
    private final AiService aiService;
    private final SessionManager sessionManager;
    private final StringRedisTemplate redisTemplate;
    private final PushService pushService;
    private final TtsService ttsService;
    private final CosService cosService;
    private final ObjectMapper objectMapper;

    /**
     * Core entry: check all guards, then generate and deliver proactive message.
     */
    public void sendProactiveMessage(Conversation conv, AiCharacter character, String triggerHint) {
        Long userId = conv.getUserId();
        Long conversationId = conv.getId();

        // Parse proactive config
        int maxDaily = 3;
        int minIntervalHours = 2;
        try {
            if (character.getProactiveConfig() != null && !character.getProactiveConfig().isBlank()) {
                JsonNode cfg = objectMapper.readTree(character.getProactiveConfig());
                if (cfg.has("max_daily")) maxDaily = cfg.get("max_daily").asInt(3);
                if (cfg.has("min_interval_hours")) minIntervalHours = cfg.get("min_interval_hours").asInt(2);
            }
        } catch (Exception e) {
            log.warn("解析 proactive_config 失败, characterId={}", character.getId(), e);
        }

        // Guard: rate limit (daily count)
        if (isRateLimited(userId, maxDaily)) {
            log.debug("主动消息被限流，今日已达上限，userId={}", userId);
            return;
        }

        // Guard: last proactive message unanswered
        if (hasUnansweredProactive(conversationId)) {
            log.debug("上条主动消息未回复，跳过，conversationId={}", conversationId);
            return;
        }

        // Guard: min interval
        if (isTooSoon(userId, minIntervalHours)) {
            log.debug("发送间隔过短，跳过，userId={}", userId);
            return;
        }

        // Update rate limiting keys
        incrementDailyCount(userId);
        updateLastTimestamp(userId);

        // Generate AI message async
        final int finalMaxDaily = maxDaily;
        CompletableFuture.runAsync(() -> {
            try {
                String aiReply = aiService.chatProactive(character, conversationId, triggerHint);
                if (aiReply == null || aiReply.isBlank()) {
                    log.warn("AI 主动消息内容为空，跳过，conversationId={}", conversationId);
                    return;
                }

                // Extract emotion tags and action descriptions
                var extracted = TextProcessor.extract(aiReply);
                String cleanContent = extracted.cleanText();

                // Defensive: if cleaned content is blank (AI replied only with tags), skip
                if (!hasNonBlankContent(cleanContent)) {
                    log.warn("主动消息清洗后内容为空，跳过保存。conversationId={}, aiReply={}",
                            conversationId, aiReply);
                    return;
                }

                // Save message to DB
                Message proactiveMsg = new Message();
                proactiveMsg.setConversationId(conversationId);
                proactiveMsg.setSenderType(SenderType.AI);
                proactiveMsg.setSource("proactive");

                // TTS flow
                if (ttsService.isEnabled()) {
                    byte[] audioData = ttsService.synthesize(cleanContent, extracted.emotion(), null);
                    if (audioData != null) {
                        proactiveMsg.setContentType(MessageContentType.VOICE);
                        proactiveMsg.setContent(cleanContent);
                        messageRepository.save(proactiveMsg);

                        String cosKey = CosService.buildAiVoiceKey(conversationId, proactiveMsg.getId());
                        String mediaUrl = cosService.upload(audioData, cosKey, "audio/mpeg");
                        proactiveMsg.setMediaUrl(mediaUrl);
                        messageRepository.save(proactiveMsg);
                        log.info("主动语音消息生成完成: convId={}, msgId={}", conversationId, proactiveMsg.getId());
                    } else {
                        // TTS failed, fallback to text
                        proactiveMsg.setContentType(MessageContentType.TEXT);
                        proactiveMsg.setContent(cleanContent);
                        messageRepository.save(proactiveMsg);
                    }
                } else {
                    proactiveMsg.setContentType(MessageContentType.TEXT);
                    proactiveMsg.setContent(cleanContent);
                    messageRepository.save(proactiveMsg);
                }

                // Update conversation unread count & lastMessageAt
                conv.setLastMessageAt(LocalDateTime.now());
                conv.setUnreadCount(conv.getUnreadCount() + 1);
                conversationRepository.save(conv);

                // Deliver
                Optional<WebSocketSession> session = sessionManager.getSession(userId);
                if (session.isPresent()) {
                    deliverOnline(session.get(), conv, proactiveMsg);
                } else {
                    pushService.sendPush(userId, character.getName() + "：" + cleanContent);
                }

            } catch (Exception e) {
                log.error("发送主动消息失败，conversationId={}", conversationId, e);
            }
        });
    }

    /**
     * Check if the cleaned content (after TextProcessor.extract) is sendable.
     * Blank content can result from AI replying with only emotion/action tags.
     */
    static boolean hasNonBlankContent(String cleanContent) {
        return cleanContent != null && !cleanContent.isBlank();
    }

    /**
     * Check if user has exceeded daily proactive message limit.
     *
     * @param userId   target user
     * @param maxDaily maximum allowed per day
     * @return true if limited (count >= maxDaily)
     */
    public boolean isRateLimited(Long userId, int maxDaily) {
        String key = DAILY_COUNT_PREFIX + userId;
        String countStr = redisTemplate.opsForValue().get(key);
        if (countStr == null) return false;
        try {
            return Integer.parseInt(countStr) >= maxDaily;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Check if the last message in the conversation is an unanswered proactive AI message.
     */
    public boolean hasUnansweredProactive(Long conversationId) {
        List<Message> messages = messageRepository
                .findByConversationIdOrderByIdDesc(conversationId, PageRequest.of(0, 1));
        if (messages.isEmpty()) return false;
        Message last = messages.get(0);
        return SenderType.AI.equals(last.getSenderType()) && "proactive".equals(last.getSource());
    }

    /**
     * Check if the min interval since last proactive has not elapsed.
     *
     * @param userId           target user
     * @param minIntervalHours minimum hours between proactive messages
     * @return true if too soon (interval not elapsed)
     */
    public boolean isTooSoon(Long userId, int minIntervalHours) {
        String key = LAST_TS_PREFIX + userId;
        String tsStr = redisTemplate.opsForValue().get(key);
        if (tsStr == null) return false;
        try {
            long lastTs = Long.parseLong(tsStr);
            long elapsedMs = System.currentTimeMillis() - lastTs;
            return elapsedMs < (long) minIntervalHours * 3600 * 1000;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private void incrementDailyCount(Long userId) {
        String key = DAILY_COUNT_PREFIX + userId;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1) {
            // First message today: set TTL to end of day
            LocalTime now = LocalTime.now();
            long secondsUntilMidnight = ChronoUnit.SECONDS.between(now, LocalTime.MAX);
            redisTemplate.expire(key, Duration.ofSeconds(secondsUntilMidnight));
        }
    }

    private void updateLastTimestamp(Long userId) {
        String key = LAST_TS_PREFIX + userId;
        redisTemplate.opsForValue().set(key, String.valueOf(System.currentTimeMillis()),
                Duration.ofHours(24));
    }

    private void deliverOnline(WebSocketSession session, Conversation conv, Message proactiveMsg) {
        try {
            // Send typing indicator
            WsTyping typing = new WsTyping(conv.getId());
            String typingJson = objectMapper.writeValueAsString(typing);
            synchronized (session) {
                if (session.isOpen()) session.sendMessage(new TextMessage(typingJson));
            }

            // Delay to simulate natural typing
            long delay = calculateTypingDelay(proactiveMsg.getContent());
            Thread.sleep(delay);

            // Send the message
            MessageData data = toData(proactiveMsg);
            WsNewMessage newMsg = new WsNewMessage(data);
            String msgJson = objectMapper.writeValueAsString(newMsg);
            synchronized (session) {
                if (session.isOpen()) session.sendMessage(new TextMessage(msgJson));
            }
        } catch (Exception e) {
            log.error("在线投递主动消息失败，userId={}", proactiveMsg.getConversationId(), e);
        }
    }

    private long calculateTypingDelay(String content) {
        if (content == null || content.isEmpty()) return 1000;
        int perCharMs = ThreadLocalRandom.current().nextInt(80, 130);
        return Math.min((long) content.length() * perCharMs, 5000);
    }

    private MessageData toData(Message msg) {
        MessageData data = new MessageData();
        data.setId(msg.getId());
        data.setConversationId(msg.getConversationId());
        data.setSenderType(msg.getSenderType());
        data.setContentType(msg.getContentType());
        data.setContent(msg.getContent());
        data.setSource(msg.getSource());
        data.setCreatedAt(msg.getCreatedAt());
        return data;
    }
}
