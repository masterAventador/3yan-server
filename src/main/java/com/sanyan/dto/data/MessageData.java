package com.sanyan.dto.data;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class MessageData {
    private Long id;
    private Long conversationId;
    private String senderType;
    private String contentType;
    private String content;
    private String source;
    private String mediaUrl;
    private Integer duration;
    private String fallbackReason;
    private LocalDateTime createdAt;
}
