package com.sanyan.controller;

import com.sanyan.auth.LoginUser;
import com.sanyan.dto.ApiResponse;
import com.sanyan.dto.data.ConversationData;
import com.sanyan.dto.data.MessageData;
import com.sanyan.entity.Message;
import com.sanyan.repository.ConversationRepository;
import com.sanyan.service.MessageService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/conversations")
@RequiredArgsConstructor
public class ConversationController {

    private final MessageService messageService;
    private final ConversationRepository conversationRepository;

    @GetMapping
    public ApiResponse<List<ConversationData>> list(@LoginUser Long userId) {
        List<ConversationData> conversations = messageService.getUserConversations(userId);
        return ApiResponse.ok(conversations);
    }

    @GetMapping("/{id}/messages")
    public ApiResponse<List<MessageData>> messages(
            @PathVariable Long id,
            @RequestParam(required = false) Long beforeId,
            @RequestParam(defaultValue = "20") int limit,
            @LoginUser Long userId) {
        try {
            List<Message> messages = messageService.getHistoryMessages(userId, id, beforeId, limit);
            List<MessageData> data = messages.stream().map(messageService::toData).toList();
            return ApiResponse.ok(data);
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @PostMapping("/{id}/read")
    public ApiResponse<Void> markRead(@PathVariable Long id, @LoginUser Long userId) {
        conversationRepository.findById(id).ifPresent(conv -> {
            if (conv.getUserId().equals(userId)) {
                conv.setUnreadCount(0);
                conversationRepository.save(conv);
            }
        });
        return ApiResponse.ok();
    }
}
