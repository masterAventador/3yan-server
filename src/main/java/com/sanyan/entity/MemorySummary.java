package com.sanyan.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "memory_summary", indexes = {
    @Index(name = "idx_memory_summary_conv", columnList = "conversationId")
})
public class MemorySummary {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long conversationId;
    @Column(nullable = false, columnDefinition = "TEXT")
    private String summary;
    @Column(length = 50)
    private String messageRange;
    @CreationTimestamp
    private LocalDateTime createdAt;
}
