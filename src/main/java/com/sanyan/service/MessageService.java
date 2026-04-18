package com.sanyan.service;

import com.sanyan.dto.ws.MessageContentType;
import com.sanyan.dto.ws.SenderType;
import com.sanyan.dto.data.ConversationData;
import com.sanyan.dto.data.MessageData;
import com.sanyan.entity.AiCharacter;
import com.sanyan.entity.Conversation;
import com.sanyan.entity.Message;
import com.sanyan.repository.AiCharacterRepository;
import com.sanyan.repository.ConversationRepository;
import com.sanyan.repository.MessageRepository;
import com.sanyan.util.TextProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class MessageService {

    private final MessageRepository messageRepository;
    private final ConversationRepository conversationRepository;
    private final AiCharacterRepository characterRepository;
    private final AiService aiService;
    private final TtsService ttsService;
    private final CosService cosService;
    private final AsrService asrService;
    private final StringRedisTemplate redisTemplate;

    /**
     * Handle user message: save user msg, call AI, save AI reply, return AI Message
     */
    public Message handleUserMessage(Long userId, Long conversationId, String contentType, String content,
                                      String mediaUrl, Integer duration) {
        Conversation conv = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new RuntimeException("会话不存在"));

        // Save user message
        Message userMsg = new Message();
        userMsg.setConversationId(conversationId);
        userMsg.setSenderType(SenderType.USER);
        userMsg.setContentType(contentType);
        userMsg.setContent(content != null ? content : "");
        userMsg.setMediaUrl(mediaUrl);
        userMsg.setDuration(duration);
        userMsg.setSource("reply");
        messageRepository.save(userMsg);
        log.info("用户消息已保存: userId={}, convId={}, msgId={}", userId, conversationId, userMsg.getId());

        // Call AI
        AiCharacter character = characterRepository.findById(conv.getCharacterId())
                .orElseThrow(() -> new RuntimeException("角色不存在"));
        log.info("调用豆包 AI: convId={}, character={}", conversationId, character.getName());
        String aiReply;
        if (MessageContentType.VOICE.equals(contentType)) {
            // 尝试 ASR 转写
            String transcribedText = asrService.isEnabled()
                    ? asrService.transcribe(mediaUrl)
                    : null;

            if (transcribedText != null && !transcribedText.isBlank()) {
                // ASR 成功：回填 content 字段，走正常对话
                userMsg.setContent(transcribedText);
                messageRepository.save(userMsg);
                log.info("ASR 转写成功: convId={}, msgId={}, text={}",
                        conversationId, userMsg.getId(), transcribedText);
                aiReply = aiService.chat(character, conversationId);
            } else {
                // ASR 失败或静音：降级到 chatVoiceAck
                log.info("ASR 失败或静音，降级为 chatVoiceAck: convId={}", conversationId);
                aiReply = aiService.chatVoiceAck(character, conversationId);
            }
        } else {
            aiReply = aiService.chat(character, conversationId);
        }
        log.info("豆包 AI 回复: convId={}, replyLength={}", conversationId, aiReply.length());

        // TTS flow: when enabled, try to synthesize voice message
        if (ttsService.isEnabled()) {
            var extracted = TextProcessor.extract(aiReply);
            String messageContent = extracted.cleanText();

            byte[] audioData = ttsService.synthesize(messageContent, extracted.ttsStyle());
            if (audioData != null) {
                // Save voice message first to get ID for COS path
                Message aiMsg = new Message();
                aiMsg.setConversationId(conversationId);
                aiMsg.setSenderType(SenderType.AI);
                aiMsg.setContentType(MessageContentType.VOICE);
                aiMsg.setContent(messageContent);
                aiMsg.setTtsStyle(extracted.ttsStyle());
                aiMsg.setSource("reply");
                // 估算语音时长：中文 TTS 约 5 字/秒，至少 1 秒、最多 60 秒
                aiMsg.setDuration(estimateVoiceDuration(messageContent));
                messageRepository.save(aiMsg);

                String cosKey = CosService.buildAiVoiceKey(conversationId, aiMsg.getId());
                String aiMediaUrl = cosService.upload(audioData, cosKey, "audio/mpeg");
                aiMsg.setMediaUrl(aiMediaUrl);
                messageRepository.save(aiMsg);
                log.info("语音消息生成完成: convId={}, msgId={}, mediaUrl={}", conversationId, aiMsg.getId(), aiMediaUrl);

                // Update conversation + Redis tracking
                conv.setLastMessageAt(LocalDateTime.now());
                conv.setUnreadCount(conv.getUnreadCount() + 1);
                conversationRepository.save(conv);

                String roundKey = "conv:round:" + conversationId;
                String roundTsKey = "conv:round:" + conversationId + ":ts";
                redisTemplate.opsForList().rightPush(roundKey, String.valueOf(userMsg.getId()));
                redisTemplate.opsForList().rightPush(roundKey, String.valueOf(aiMsg.getId()));
                redisTemplate.opsForValue().set(roundTsKey, String.valueOf(System.currentTimeMillis()));

                return aiMsg;
            }
            // TTS failed, fall through to text mode
            log.warn("TTS 合成失败，降级为文字消息: convId={}", conversationId);
        }

        // Text message (TTS disabled or degraded)
        // Still need to strip emotion tags and action descriptions from text
        var extracted = TextProcessor.extract(aiReply);
        Message aiMsg = new Message();
        aiMsg.setConversationId(conversationId);
        aiMsg.setSenderType("ai");
        aiMsg.setContentType(MessageContentType.TEXT);
        aiMsg.setContent(extracted.cleanText());
        aiMsg.setSource("reply");
        messageRepository.save(aiMsg);
        log.info("AI 回复已保存: convId={}, msgId={}", conversationId, aiMsg.getId());

        // Update conversation
        conv.setLastMessageAt(LocalDateTime.now());
        conv.setUnreadCount(conv.getUnreadCount() + 1);
        conversationRepository.save(conv);

        // Track message IDs in Redis for memory round detection
        String roundKey = "conv:round:" + conversationId;
        String roundTsKey = "conv:round:" + conversationId + ":ts";
        redisTemplate.opsForList().rightPush(roundKey, String.valueOf(userMsg.getId()));
        redisTemplate.opsForList().rightPush(roundKey, String.valueOf(aiMsg.getId()));
        redisTemplate.opsForValue().set(roundTsKey, String.valueOf(System.currentTimeMillis()));

        return aiMsg;
    }

    /**
     * Calculate typing delay based on content length
     */
    public long calculateTypingDelay(String content) {
        if (content == null || content.isEmpty()) {
            return 1000;
        }
        int perCharMs = ThreadLocalRandom.current().nextInt(100, 151);
        long delay = (long) content.length() * perCharMs;
        return Math.min(delay, 8000);
    }

    /**
     * Get user's conversations with character info
     */
    public List<ConversationData> getUserConversations(Long userId) {
        List<Conversation> conversations = conversationRepository.findByUserIdOrderByLastMessageAtDesc(userId);
        return conversations.stream().map(this::toConversationData).toList();
    }

    /**
     * Get or create conversation for user + character
     */
    public Conversation getOrCreateConversation(Long userId, Long characterId) {
        return conversationRepository.findByUserIdAndCharacterId(userId, characterId)
                .orElseGet(() -> {
                    Conversation conv = new Conversation();
                    conv.setUserId(userId);
                    conv.setCharacterId(characterId);
                    conv.setLastMessageAt(LocalDateTime.now());
                    conv.setUnreadCount(0);
                    return conversationRepository.save(conv);
                });
    }

    /**
     * Sync messages after given message ID
     */
    public List<Message> syncMessages(Long conversationId, Long afterMsgId, int limit) {
        if (afterMsgId == null || afterMsgId <= 0) {
            List<Message> messages = messageRepository.findByConversationIdOrderByIdDesc(conversationId, PageRequest.of(0, limit));
            Collections.reverse(messages);
            return messages;
        }
        return messageRepository.findByConversationIdAndIdGreaterThanOrderByIdAsc(
                conversationId, afterMsgId, PageRequest.of(0, limit));
    }

    /**
     * Get history messages before given message ID (cursor pagination)
     */
    public List<Message> getHistoryMessages(Long conversationId, Long beforeMsgId, int limit) {
        List<Message> messages;
        if (beforeMsgId == null || beforeMsgId <= 0) {
            messages = messageRepository.findByConversationIdOrderByIdDesc(conversationId, PageRequest.of(0, limit));
        } else {
            messages = messageRepository.findByConversationIdAndIdLessThanOrderByIdDesc(
                    conversationId, beforeMsgId, PageRequest.of(0, limit));
        }
        Collections.reverse(messages);
        return messages;
    }

    /**
     * 按文本长度估算 TTS 语音时长（秒）。
     * 中文语速约 5 字/秒，截断到 [1, 60]。
     */
    static int estimateVoiceDuration(String text) {
        if (text == null || text.isBlank()) return 1;
        int seconds = Math.max(1, Math.round(text.length() / 5.0f));
        return Math.min(60, seconds);
    }

    public MessageData toData(Message msg) {
        MessageData d = new MessageData();
        d.setId(msg.getId());
        d.setConversationId(msg.getConversationId());
        d.setSenderType(msg.getSenderType());
        d.setContentType(msg.getContentType());
        d.setContent(msg.getContent());
        d.setSource(msg.getSource());
        d.setMediaUrl(msg.getMediaUrl());
        d.setDuration(msg.getDuration());
        d.setCreatedAt(msg.getCreatedAt());
        return d;
    }

    private ConversationData toConversationData(Conversation conv) {
        ConversationData d = new ConversationData();
        d.setId(conv.getId());
        d.setCharacterId(conv.getCharacterId());
        d.setLastMessageAt(conv.getLastMessageAt());
        d.setUnreadCount(conv.getUnreadCount());

        // Fill character info
        characterRepository.findById(conv.getCharacterId()).ifPresent(c -> {
            d.setCharacterName(c.getName());
            d.setCharacterAvatar(c.getAvatar());
        });

        // Fill last message preview
        List<Message> lastMsgs = messageRepository.findByConversationIdOrderByIdDesc(
                conv.getId(), PageRequest.of(0, 1));
        if (!lastMsgs.isEmpty()) {
            d.setLastMessage(lastMsgs.get(0).getContent());
        }

        return d;
    }
}
