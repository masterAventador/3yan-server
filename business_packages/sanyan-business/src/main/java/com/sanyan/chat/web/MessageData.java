package com.sanyan.chat.web;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class MessageData {
    private Long id;
    private String senderType;
    private String content;
    private LocalDateTime createdAt;
}
