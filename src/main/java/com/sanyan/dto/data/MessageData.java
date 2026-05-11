package com.sanyan.dto.data;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class MessageData {
    private Long id;
    private String senderType;
    private String content;
    private LocalDateTime createdAt;
}
