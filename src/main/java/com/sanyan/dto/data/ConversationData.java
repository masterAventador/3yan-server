package com.sanyan.dto.data;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class ConversationData {
    private Long id;
    private Long characterId;
    private String characterName;
    private String characterAvatar;
    private String lastMessage;
    private LocalDateTime lastMessageAt;
    private Integer unreadCount;
}
