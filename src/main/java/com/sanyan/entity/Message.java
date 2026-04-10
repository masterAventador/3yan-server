package com.sanyan.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "message", indexes = {
    @Index(name = "idx_message_conversation", columnList = "conversationId,id")
})
public class Message {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long conversationId;
    @Column(nullable = false, length = 10)
    private String senderType; // user / ai
    @Column(nullable = false, length = 10)
    private String contentType; // text / voice
    @Column(columnDefinition = "TEXT")
    private String content;
    private String mediaUrl;
    @Column
    private Integer duration;
    @Column(nullable = false, length = 10)
    private String source; // reply / proactive
    @CreationTimestamp
    private LocalDateTime createdAt;
}
