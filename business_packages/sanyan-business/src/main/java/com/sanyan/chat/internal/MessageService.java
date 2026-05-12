package com.sanyan.chat.internal;

import com.sanyan.chat.web.MessageData;
import com.sanyan.chat.ws.SenderType;
import com.sanyan.character.internal.AiCharacterEntity;
import com.sanyan.character.internal.AiCharacterRepository;
import com.sanyan.character.internal.CharacterErrCode;
import com.sanyan.common.error.BusinessException;
import com.sanyan.common.util.TextProcessor;
import com.sanyan.llm.internal.AiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
     * 落 user message → 返回 server 端 id（独立事务，便于 ack 即时回传 serverMsgId）
     */
    @Transactional
    public MessageEntity saveUserMessage(Long userId, String content) {
        MessageEntity userMsg = new MessageEntity();
        userMsg.setUserId(userId);
        userMsg.setSenderType(SenderType.USER);
        userMsg.setContent(content != null ? content : "");
        messageRepository.save(userMsg);
        log.info("用户消息已保存: userId={}, msgId={}", userId, userMsg.getId());
        return userMsg;
    }

    /** AI 回复最多拆分多少条气泡（Plan-1 F7.3） */
    public static final int MAX_AI_BUBBLES = 4;

    /**
     * 调 AI 拿回复 → 清理动作描述 → 按句号/换行拆分 → 多条独立落库 → 返回有序消息列表
     */
    @Transactional
    public List<MessageEntity> handleAiReply(Long userId) {
        AiCharacterEntity character = characterRepository.findById(DEFAULT_CHARACTER_ID)
                .orElseThrow(() -> new BusinessException(CharacterErrCode.CHARACTER_NOT_FOUND));
        log.info("调用豆包 AI: userId={}, character={}", userId, character.getName());
        String rawReply = aiService.chat(character, userId);
        String cleaned = TextProcessor.cleanAiReply(rawReply);
        List<String> bubbles = TextProcessor.splitIntoBubbles(cleaned, MAX_AI_BUBBLES);
        log.info("豆包 AI 回复: userId={}, rawLen={}, bubbles={}", userId, rawReply.length(), bubbles.size());

        if (bubbles.isEmpty()) return Collections.emptyList();

        List<MessageEntity> saved = new ArrayList<>(bubbles.size());
        for (String bubble : bubbles) {
            MessageEntity aiMsg = new MessageEntity();
            aiMsg.setUserId(userId);
            aiMsg.setSenderType(SenderType.AI);
            aiMsg.setContent(bubble);
            messageRepository.save(aiMsg);
            saved.add(aiMsg);
        }
        log.info("AI 回复已保存: userId={}, msgIds={}", userId, saved.stream().map(MessageEntity::getId).toList());
        return saved;
    }

    /**
     * 拉取 lastMsgId 之后的所有消息（WS sync 用）
     */
    public List<MessageEntity> syncMessages(Long userId, Long afterMsgId, int limit) {
        if (afterMsgId == null || afterMsgId <= 0) {
            List<MessageEntity> messages = messageRepository.findByUserIdOrderByIdDesc(
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
    public List<MessageEntity> getHistoryMessages(Long userId, Long beforeMsgId, int limit) {
        List<MessageEntity> messages;
        if (beforeMsgId == null || beforeMsgId <= 0) {
            messages = messageRepository.findByUserIdOrderByIdDesc(userId, PageRequest.of(0, limit));
        } else {
            messages = messageRepository.findByUserIdAndIdLessThanOrderByIdDesc(
                    userId, beforeMsgId, PageRequest.of(0, limit));
        }
        Collections.reverse(messages);
        return messages;
    }

    public MessageData toData(MessageEntity msg) {
        MessageData d = new MessageData();
        d.setId(msg.getId());
        d.setSenderType(msg.getSenderType());
        d.setContent(msg.getContent());
        d.setCreatedAt(msg.getCreatedAt());
        return d;
    }
}
