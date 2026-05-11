package com.sanyan.service;

import com.sanyan.dto.data.MessageData;
import com.sanyan.dto.ws.SenderType;
import com.sanyan.entity.AiCharacter;
import com.sanyan.entity.Message;
import com.sanyan.repository.AiCharacterRepository;
import com.sanyan.repository.MessageRepository;
import com.sanyan.util.TextProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class MessageService {

    /**
     * MVP 一期单角色硬编码：林小婉。后续多角色再扩展。
     */
    private static final Long DEFAULT_CHARACTER_ID = 1L;

    private final MessageRepository messageRepository;
    private final AiCharacterRepository characterRepository;
    private final AiService aiService;

    /**
     * Handle user text message: 落 user msg → 调 AI → 落 AI msg → 返回 AI Message
     */
    @Transactional
    public Message handleUserMessage(Long userId, String content) {
        Message userMsg = new Message();
        userMsg.setUserId(userId);
        userMsg.setSenderType(SenderType.USER);
        userMsg.setContent(content != null ? content : "");
        messageRepository.save(userMsg);
        log.info("用户消息已保存: userId={}, msgId={}", userId, userMsg.getId());

        AiCharacter character = characterRepository.findById(DEFAULT_CHARACTER_ID)
                .orElseThrow(() -> new RuntimeException("默认角色不存在（character_id=1）"));
        log.info("调用豆包 AI: userId={}, character={}", userId, character.getName());
        String aiReply = aiService.chat(character, userId);
        log.info("豆包 AI 回复: userId={}, replyLength={}", userId, aiReply.length());

        Message aiMsg = new Message();
        aiMsg.setUserId(userId);
        aiMsg.setSenderType(SenderType.AI);
        aiMsg.setContent(TextProcessor.cleanAiReply(aiReply));
        messageRepository.save(aiMsg);
        log.info("AI 回复已保存: userId={}, msgId={}", userId, aiMsg.getId());

        return aiMsg;
    }

    /**
     * 模拟打字延迟（按字数估算 100-150ms/字，封顶 8s）
     */
    public long calculateTypingDelay(String content) {
        if (content == null || content.isEmpty()) return 1000;
        int perCharMs = ThreadLocalRandom.current().nextInt(100, 151);
        return Math.min((long) content.length() * perCharMs, 8000);
    }

    /**
     * 拉取 lastMsgId 之后的所有消息（WS sync 用）
     */
    public List<Message> syncMessages(Long userId, Long afterMsgId, int limit) {
        if (afterMsgId == null || afterMsgId <= 0) {
            List<Message> messages = messageRepository.findByUserIdOrderByIdDesc(
                    userId, PageRequest.of(0, limit));
            Collections.reverse(messages);
            return messages;
        }
        return messageRepository.findByUserIdAndIdGreaterThanOrderByIdAsc(
                userId, afterMsgId, PageRequest.of(0, limit));
    }

    /**
     * 拉取历史消息（HTTP /api/messages 用，beforeId 游标分页，按时间正序）
     */
    public List<Message> getHistoryMessages(Long userId, Long beforeMsgId, int limit) {
        List<Message> messages;
        if (beforeMsgId == null || beforeMsgId <= 0) {
            messages = messageRepository.findByUserIdOrderByIdDesc(userId, PageRequest.of(0, limit));
        } else {
            messages = messageRepository.findByUserIdAndIdLessThanOrderByIdDesc(
                    userId, beforeMsgId, PageRequest.of(0, limit));
        }
        Collections.reverse(messages);
        return messages;
    }

    public MessageData toData(Message msg) {
        MessageData d = new MessageData();
        d.setId(msg.getId());
        d.setSenderType(msg.getSenderType());
        d.setContent(msg.getContent());
        d.setCreatedAt(msg.getCreatedAt());
        return d;
    }
}
