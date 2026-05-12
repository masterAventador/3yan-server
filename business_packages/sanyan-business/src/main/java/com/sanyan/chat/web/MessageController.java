package com.sanyan.chat.web;

import com.sanyan.common.auth.LoginUser;
import com.sanyan.dto.ApiResponse; // TODO M3.6: 替换为 BaseResp
import com.sanyan.chat.internal.MessageEntity;
import com.sanyan.chat.internal.MessageService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/messages")
@RequiredArgsConstructor
public class MessageController {

    private final MessageService messageService;

    /**
     * 拉取历史消息（游标分页：beforeId 之前的 limit 条，按时间正序返回）
     */
    @GetMapping
    public ApiResponse<List<MessageData>> history(
            @LoginUser Long userId,
            @RequestParam(required = false) Long beforeId,
            @RequestParam(defaultValue = "20") int limit) {
        List<MessageEntity> messages = messageService.getHistoryMessages(userId, beforeId, limit);
        List<MessageData> data = messages.stream().map(messageService::toData).toList();
        return ApiResponse.ok(data);
    }
}
