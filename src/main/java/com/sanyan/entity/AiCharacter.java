package com.sanyan.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "ai_character")
public class AiCharacter {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 50)
    private String name;
    private String avatar;
    @Column(nullable = false, columnDefinition = "TEXT")
    private String systemPrompt;
    @Column(columnDefinition = "TEXT")
    private String greeting;
    @Column(columnDefinition = "TEXT")
    private String proactiveConfig;
    @Column(nullable = false, length = 10)
    private String type; // preset / custom
    private Long createdBy;
    @CreationTimestamp
    private LocalDateTime createdAt;
}
