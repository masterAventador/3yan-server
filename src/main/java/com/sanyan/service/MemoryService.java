package com.sanyan.service;

import com.sanyan.dto.ws.SenderType;
import com.sanyan.entity.MemoryProfile;
import com.sanyan.entity.MemorySummary;
import com.sanyan.entity.Message;
import com.sanyan.repository.MemoryProfileRepository;
import com.sanyan.repository.MemorySummaryRepository;
import com.sanyan.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryService {

    private final MemoryProfileRepository profileRepository;
    private final MemorySummaryRepository summaryRepository;
    private final MessageRepository messageRepository;
    private final AiService aiService;

    /**
     * Async: fetch messages by IDs, call AI for profile update + summary, persist results.
     */
    @Async
    public void updateMemory(Long conversationId, List<Long> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return;
        }
        try {
            Long minId = messageIds.stream().min(Long::compareTo).orElse(0L);
            Long maxId = messageIds.stream().max(Long::compareTo).orElse(0L);
            List<Message> messages = messageRepository.findByConversationIdAndIdBetweenOrderByIdAsc(
                    conversationId, minId, maxId);

            if (messages.isEmpty()) {
                return;
            }

            // Update user profile
            String currentProfile = profileRepository.findByConversationId(conversationId)
                    .map(MemoryProfile::getContent)
                    .orElse("");
            String profilePrompt = buildProfileUpdatePrompt(currentProfile, messages);
            String newProfile = aiService.callForMemory(profilePrompt);
            if (newProfile != null && !newProfile.isBlank()) {
                MemoryProfile profile = profileRepository.findByConversationId(conversationId)
                        .orElseGet(() -> {
                            MemoryProfile p = new MemoryProfile();
                            p.setConversationId(conversationId);
                            return p;
                        });
                profile.setContent(newProfile.trim());
                profileRepository.save(profile);
                log.info("更新用户画像，会话ID={}", conversationId);
            }

            // Generate conversation summary
            String summaryPrompt = buildSummaryPrompt(messages);
            String summaryText = aiService.callForMemory(summaryPrompt);
            if (summaryText != null && !summaryText.isBlank()) {
                MemorySummary summary = new MemorySummary();
                summary.setConversationId(conversationId);
                summary.setSummary(summaryText.trim());
                summary.setMessageRange(minId + "-" + maxId);
                summaryRepository.save(summary);
                log.info("保存对话摘要，会话ID={}，消息范围={}-{}", conversationId, minId, maxId);
            }

        } catch (Exception e) {
            log.error("记忆更新失败，会话ID={}", conversationId, e);
        }
    }

    /**
     * Build prompt for AI to update user profile based on new conversation messages.
     */
    public String buildProfileUpdatePrompt(String currentProfile, List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个信息提取助手。请根据以下新的对话内容，更新用户画像。\n\n");

        if (currentProfile != null && !currentProfile.isBlank()) {
            sb.append("【当前用户画像】\n").append(currentProfile).append("\n\n");
        }

        sb.append("【新对话内容】\n");
        for (Message msg : messages) {
            String role = SenderType.USER.equals(msg.getSenderType()) ? "用户" : "AI";
            sb.append(role).append("：").append(msg.getContent()).append("\n");
        }

        sb.append("\n请综合以上信息，输出更新后的用户画像（纯文本，简洁描述用户的基本信息、职业、兴趣爱好、性格特点等）。" +
                "只输出画像内容，不要有任何额外说明。");
        return sb.toString();
    }

    /**
     * Build prompt for AI to generate a 1-2 sentence summary of the conversation.
     */
    public String buildSummaryPrompt(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        sb.append("请用1-2句话概括以下对话的主要内容：\n\n");

        for (Message msg : messages) {
            String role = SenderType.USER.equals(msg.getSenderType()) ? "用户" : "AI";
            sb.append(role).append("：").append(msg.getContent()).append("\n");
        }

        sb.append("\n只输出摘要内容，不要有任何额外说明。");
        return sb.toString();
    }
}
