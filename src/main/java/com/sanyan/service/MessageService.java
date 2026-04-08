package com.sanyan.service;

import com.sanyan.dto.data.ConversationData;
import com.sanyan.dto.data.MessageData;
import com.sanyan.entity.AiCharacter;
import com.sanyan.entity.Conversation;
import com.sanyan.entity.Message;
import com.sanyan.repository.AiCharacterRepository;
import com.sanyan.repository.ConversationRepository;
import com.sanyan.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
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

    /**
     * Handle user message: save user msg, call AI, save AI reply, return AI Message
     */
    public Message handleUserMessage(Long userId, Long conversationId, String contentType, String content) {
        Conversation conv = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new RuntimeException("会话不存在"));

        // Save user message
        Message userMsg = new Message();
        userMsg.setConversationId(conversationId);
        userMsg.setSenderType("user");
        userMsg.setContentType(contentType);
        userMsg.setContent(content);
        userMsg.setSource("reply");
        messageRepository.save(userMsg);

        // Update conversation last message time
        conv.setLastMessageAt(LocalDateTime.now());
        conversationRepository.save(conv);

        // Call AI
        AiCharacter character = characterRepository.findById(conv.getCharacterId())
                .orElseThrow(() -> new RuntimeException("角色不存在"));
        String aiReply = aiService.chat(character, conversationId);

        // Save AI reply
        Message aiMsg = new Message();
        aiMsg.setConversationId(conversationId);
        aiMsg.setSenderType("ai");
        aiMsg.setContentType("text");
        aiMsg.setContent(aiReply);
        aiMsg.setSource("reply");
        messageRepository.save(aiMsg);

        // Update conversation
        conv.setLastMessageAt(LocalDateTime.now());
        conv.setUnreadCount(conv.getUnreadCount() + 1);
        conversationRepository.save(conv);

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

    public MessageData toData(Message msg) {
        MessageData d = new MessageData();
        d.setId(msg.getId());
        d.setConversationId(msg.getConversationId());
        d.setSenderType(msg.getSenderType());
        d.setContentType(msg.getContentType());
        d.setContent(msg.getContent());
        d.setSource(msg.getSource());
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
