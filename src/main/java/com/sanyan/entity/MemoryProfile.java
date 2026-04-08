package com.sanyan.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "memory_profile", indexes = {
    @Index(name = "idx_memory_profile_conv", columnList = "conversationId", unique = true)
})
public class MemoryProfile {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true)
    private Long conversationId;
    @Column(columnDefinition = "TEXT")
    private String content;
    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
