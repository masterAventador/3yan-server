package com.sanyan.chat.internal;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "message", indexes = {
    @Index(name = "idx_message_user", columnList = "userId,id"),
    @Index(name = "idx_message_created_at", columnList = "createdAt")
})
public class MessageEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long userId;
    @Column(nullable = false, length = 10)
    private String senderType; // user / ai
    @Column(columnDefinition = "TEXT")
    private String content;
    /** true=AI 主动推送（早安/关怀等无用户触发）；false=用户消息后的对话回复。仅用于后期排查。 */
    @Column(nullable = false)
    private boolean isProactive;
    @CreationTimestamp
    private LocalDateTime createdAt;
}
