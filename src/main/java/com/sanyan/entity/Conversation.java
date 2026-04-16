package com.sanyan.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "conversation",
    uniqueConstraints = {
        @UniqueConstraint(columnNames = {"user_id", "character_id"})
    },
    indexes = {
        @Index(name = "idx_conversation_character_id", columnList = "character_id")
    }
)
public class Conversation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long userId;
    @Column(nullable = false)
    private Long characterId;
    private LocalDateTime lastMessageAt;
    @Column(nullable = false)
    private Integer unreadCount = 0;
}
